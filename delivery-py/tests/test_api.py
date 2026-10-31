"""
HTTP-слой без базы: маршруты, проверка входа и ролей, формат ошибок —
то, на что опираются шлюз и `order`.
"""

import base64
import io
import json

import pytest

from delivery import api, auth, config, dispatch


@pytest.fixture(autouse=True)
def settings():
    config.apply(config.load_config({"AUTH_DISABLED": "true"}))
    auth.init_auth(config.current["auth"])


def token(roles, sub="11111111-1111-4111-8111-111111111111"):
    payload = json.dumps({"sub": sub, "realm_access": {"roles": roles}}).encode()
    return "e30." + base64.urlsafe_b64encode(payload).decode().rstrip("=") + ".x"


def call(method, path, body=None, headers=None):
    raw = json.dumps(body).encode() if body is not None else b""
    environ = {
        "REQUEST_METHOD": method,
        "PATH_INFO": path,
        "CONTENT_TYPE": "application/json",
        "CONTENT_LENGTH": str(len(raw)),
        "wsgi.input": io.BytesIO(raw),
    }
    environ.update(headers or {})
    response = {}
    chunks = api.application(environ, lambda status, hdrs: response.update(status=status, headers=dict(hdrs)))
    text = b"".join(chunks).decode()
    status = int(response["status"].split()[0])
    return status, (json.loads(text) if "json" in response["headers"]["Content-Type"] else text), response["headers"]


def test_health_is_public():
    status, body, _ = call("GET", "/health")
    assert status == 200 and body["status"] == "ok" and body["service"] == "delivery"


def test_unknown_route_is_404_like_nest():
    status, body, _ = call("GET", "/nope")
    assert status == 404 and body["message"] == "Cannot GET /nope"


def test_internal_api_requires_service_token():
    status, body, _ = call("POST", "/deliveries", {})
    assert status == 401 and body["code"] == "UNAUTHORIZED"


def test_internal_api_validates_request():
    status, body, _ = call(
        "POST", "/deliveries", {"orderId": "x"},
        {"HTTP_X_INTERNAL_TOKEN": config.DEV_INTERNAL_TOKEN},
    )
    assert status == 400 and body["code"] == "VALIDATION_FAILED"


def test_courier_routes_require_login():
    status, body, _ = call("GET", "/couriers/me")
    assert status == 401 and body["message"] == "требуется вход в систему"


def test_role_is_checked():
    status, body, _ = call("GET", "/couriers", headers={"HTTP_AUTHORIZATION": "Bearer " + token(["client"])})
    assert status == 403
    assert body["details"] == {"required": ["admin"], "actual": ["client"]}


def test_unknown_roles_are_dropped():
    user = auth.verify_token(token(["courier", "uma_authorization"]))
    assert user["roles"] == ["courier"]


def test_shift_needs_boolean():
    status, body, _ = call(
        "POST", "/couriers/me/shift", {"onShift": "yes"},
        {"HTTP_AUTHORIZATION": "Bearer " + token(["courier"])},
    )
    assert status == 400 and body["message"] == "onShift: true или false"


def test_correlation_id_is_echoed_when_safe():
    _, body, headers = call("GET", "/nope", headers={"HTTP_X_CORRELATION_ID": "abc-123"})
    assert headers["x-correlation-id"] == "abc-123" and body["correlationId"] == "abc-123"
    _, _, unsafe = call("GET", "/nope", headers={"HTTP_X_CORRELATION_ID": "bad id <script>"})
    assert unsafe["x-correlation-id"] != "bad id <script>"


def test_service_failure_hides_details(monkeypatch):
    def broken(request, now=None):
        raise RuntimeError("пароль от базы: секрет")

    monkeypatch.setattr(dispatch, "request_courier", broken)
    status, body, _ = call(
        "POST", "/deliveries", {}, {"HTTP_X_INTERNAL_TOKEN": config.DEV_INTERNAL_TOKEN}
    )
    assert status == 500 and body["message"] == "внутренняя ошибка сервиса"
    assert "секрет" not in json.dumps(body, ensure_ascii=False)


def test_metrics_count_requests():
    call("GET", "/health")
    status, text, _ = call("GET", "/metrics")
    assert status == 200 and 'route="/health"' in text


def test_production_refuses_disabled_auth():
    with pytest.raises(RuntimeError):
        config.load_config({"NODE_ENV": "production", "AUTH_DISABLED": "true"})
    with pytest.raises(RuntimeError):
        config.load_config({"NODE_ENV": "production"})
