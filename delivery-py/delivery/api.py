"""
HTTP API сервиса — WSGI-приложение, то есть обычная функция
`application(environ, start_response)`.

Маршруты, коды ответов, проверка прав и формат ошибок те же, что у
TS-версии, поэтому шлюз и `order` работают с сервисом без изменений:

    внутренний API (служебный токен):  POST /deliveries, POST /deliveries/release
    курьер:          GET /couriers/me, POST /couriers/me/shift, POST /couriers/me/location,
                     POST /offers/{id}/accept|reject, POST /deliveries/{id}/picked-up|delivered
    клиент/оператор: GET /orders/{orderId}/courier-position
    администратор:   GET /couriers, POST /couriers
    служебные:       GET /health, GET /ready, GET /metrics

Обработчик закрыт, пока не помечен как открытый (SEC-2): доступ каждого
маршрута записан в таблице маршрутов явно.
"""

import json
import re
import threading
import time
import traceback
from datetime import datetime

from . import auth, clock, config, couriers, db, dispatch, errors, log, tracking
from .context import accept_correlation_id, correlation

SERVICE = "delivery"
STARTED = time.monotonic()

STATUS_TEXT = {
    200: "OK", 201: "Created", 202: "Accepted", 400: "Bad Request", 401: "Unauthorized",
    403: "Forbidden", 404: "Not Found", 409: "Conflict", 422: "Unprocessable Entity",
    500: "Internal Server Error", 502: "Bad Gateway", 503: "Service Unavailable",
}

# Показатели запросов для /metrics: (метод, маршрут, код) → [число, сумма секунд].
_metrics = {}
_metrics_lock = threading.Lock()


# ─────────────────────────── вспомогательное ───────────────────────────


def _json_default(value):
    if isinstance(value, datetime):
        return clock.iso(value)
    raise TypeError(f"{type(value).__name__} не сериализуется в JSON")


def encode_json(body):
    return json.dumps(body, ensure_ascii=False, separators=(",", ":"), default=_json_default).encode()


def _user_id(request):
    user = request["user"]
    if not user:
        raise errors.unauthorized("требуется вход в систему")
    return user["id"]


def _body(request):
    body = request["body"]
    return body if isinstance(body, dict) else {}


# ─────────────────────────── обработчики ───────────────────────────


def handle_health(request):
    return {"status": "ok", "service": SERVICE, "uptimeSec": round(time.monotonic() - STARTED)}


def handle_ready(request):
    checks = {"database": db.readiness()}
    if any(check["status"] == "fail" for check in checks.values()):
        return 503, {"status": "not_ready", "service": SERVICE, "checks": checks}
    return {"status": "ready", "service": SERVICE, "checks": checks}


def handle_metrics(request):
    lines = [
        "# HELP http_requests_total Число обработанных запросов.",
        "# TYPE http_requests_total counter",
    ]
    with _metrics_lock:
        snapshot = dict(_metrics)
    for (method, route, status), (count, _) in sorted(snapshot.items()):
        labels = f'service="{SERVICE}",method="{method}",route="{route}",status="{status}"'
        lines.append(f"http_requests_total{{{labels}}} {count}")
    lines += [
        "# HELP http_request_duration_seconds Время обработки запросов.",
        "# TYPE http_request_duration_seconds summary",
    ]
    for (method, route, status), (count, seconds) in sorted(snapshot.items()):
        labels = f'service="{SERVICE}",method="{method}",route="{route}",status="{status}"'
        lines.append(f"http_request_duration_seconds_sum{{{labels}}} {seconds:.6f}")
        lines.append(f"http_request_duration_seconds_count{{{labels}}} {count}")
    return 200, "\n".join(lines) + "\n"


def handle_request_courier(request):
    return dispatch.request_courier(request["body"])


def handle_release_courier(request):
    return dispatch.release_courier(request["body"])


def handle_dashboard(request):
    return couriers.dashboard(_user_id(request))


def handle_shift(request):
    on_shift = _body(request).get("onShift")
    if not isinstance(on_shift, bool):
        raise errors.validation("onShift: true или false")
    return couriers.set_shift(_user_id(request), on_shift)


def handle_location(request):
    return couriers.update_location(_user_id(request), request["body"])


def handle_accept(request):
    return couriers.accept(_user_id(request), request["params"]["offerId"])


def handle_reject(request):
    return couriers.reject(_user_id(request), request["params"]["offerId"])


def handle_picked_up(request):
    return couriers.picked_up(_user_id(request), request["params"]["deliveryId"])


def handle_delivered(request):
    return couriers.delivered(_user_id(request), request["params"]["deliveryId"])


def handle_courier_position(request):
    user = request["user"]
    if not user:
        raise errors.unauthorized("требуется вход в систему")
    return tracking.courier_position(request["params"]["orderId"], user["id"], user["roles"])


def handle_list_couriers(request):
    return tracking.list_couriers()


def handle_register_courier(request):
    return tracking.register_courier(request["body"])


# ─────────────────────────── таблица маршрутов ───────────────────────────

COURIER = ("courier",)


def _route(method, path, access, roles, handler, status=200):
    pattern = re.sub(r"\{(\w+)\}", r"(?P<\1>[^/]+)", path)
    return {
        "method": method,
        "path": path,
        "pattern": re.compile(pattern),
        # public — без входа; internal — служебный токен; user — токен пользователя
        "access": access,
        "roles": roles,
        "handler": handler,
        "status": status,
    }


ROUTES = [
    _route("GET", "/health", "public", (), handle_health),
    _route("GET", "/ready", "public", (), handle_ready),
    _route("GET", "/metrics", "public", (), handle_metrics),
    _route("POST", "/deliveries", "internal", (), handle_request_courier),
    _route("POST", "/deliveries/release", "internal", (), handle_release_courier),
    _route("GET", "/couriers/me", "user", COURIER, handle_dashboard),
    _route("POST", "/couriers/me/shift", "user", COURIER, handle_shift),
    _route("POST", "/couriers/me/location", "user", COURIER, handle_location, 202),
    _route("POST", "/offers/{offerId}/accept", "user", COURIER, handle_accept),
    _route("POST", "/offers/{offerId}/reject", "user", COURIER, handle_reject),
    _route("POST", "/deliveries/{deliveryId}/picked-up", "user", COURIER, handle_picked_up),
    _route("POST", "/deliveries/{deliveryId}/delivered", "user", COURIER, handle_delivered),
    _route(
        "GET", "/orders/{orderId}/courier-position", "user",
        ("client", "operator", "admin"), handle_courier_position,
    ),
    _route("GET", "/couriers", "user", ("admin",), handle_list_couriers),
    _route("POST", "/couriers", "user", ("admin",), handle_register_courier, 201),
]


def match_route(method, path):
    for route in ROUTES:
        found = route["pattern"].fullmatch(path)
        if found and route["method"] == method:
            return route, found.groupdict()
    return None, {}


# ─────────────────────────── обработка запроса ───────────────────────────


def authorize(route, environ):
    """Кто выполняет запрос; исключение — если доступа нет."""
    token = auth.extract_bearer(environ.get("HTTP_AUTHORIZATION"))
    if route["access"] in ("public", "internal"):
        # У открытого метода токен не обязателен, но если пришёл — разбирается.
        user = None
        if token:
            try:
                user = auth.verify_token(token)
            except Exception:  # noqa: BLE001 — неверный токен на открытом методе не мешает
                user = None
        if route["access"] == "internal":
            auth.check_internal_token(
                environ.get("HTTP_X_INTERNAL_TOKEN"), config.current["internal_token"]
            )
        return user

    if not token:
        raise errors.unauthorized("требуется вход в систему")
    user = auth.verify_token(token)
    required = list(route["roles"])
    if required and not any(role in user["roles"] for role in required):
        raise errors.forbidden(
            "недостаточно прав для этого действия", {"required": required, "actual": user["roles"]}
        )
    return user


def read_body(environ):
    """Тело JSON. Пустое или не JSON по типу — пустой объект, как у Express."""
    try:
        length = int(environ.get("CONTENT_LENGTH") or 0)
    except ValueError:
        length = 0
    if length <= 0 or "json" not in (environ.get("CONTENT_TYPE") or ""):
        return {}
    raw = environ["wsgi.input"].read(length)
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise errors.validation("тело запроса — не JSON") from error


def _error_body(error, correlation_id):
    info = errors.app_info(error)
    return {
        "code": info["code"],
        "message": info["message"],
        "details": info["details"],
        "service": SERVICE,
        "correlationId": correlation_id,
    }


def handle(method, path, environ, correlation_id):
    """(код, тело, маршрут) для запроса. Тело — словарь или текст."""
    route, params = match_route(method, path)
    if not route:
        return 404, {
            "message": f"Cannot {method} {path}",
            "error": "Not Found",
            "statusCode": 404,
            "service": SERVICE,
            "correlationId": correlation_id,
        }, None
    try:
        user = authorize(route, environ)
        body = read_body(environ) if method in ("POST", "PUT", "PATCH") else {}
        result = route["handler"]({"params": params, "body": body, "user": user})
        if isinstance(result, tuple):
            return result[0], result[1], route
        return route["status"], result, route
    except Exception as error:  # noqa: BLE001 — любой сбой превращается в ответ
        if errors.app_info(error):
            return errors.app_info(error)["status"], _error_body(error, correlation_id), route
        # Сбой: подробности — в журнал, наружу — общее сообщение.
        log.error(
            "необработанная ошибка",
            error=repr(error),
            stack=traceback.format_exc(limit=8),
        )
        return 500, {
            "code": "INTERNAL",
            "message": "внутренняя ошибка сервиса",
            "service": SERVICE,
            "correlationId": correlation_id,
        }, route


def _record(method, route_path, status, seconds):
    key = (method, route_path, status)
    with _metrics_lock:
        count, total = _metrics.get(key, (0, 0.0))
        _metrics[key] = (count + 1, total + seconds)


def application(environ, start_response):
    started = time.monotonic()
    method = environ.get("REQUEST_METHOD", "GET").upper()
    # PATH_INFO в WSGI — байты пути в latin-1; адреса сервиса — ASCII, но декодируем честно.
    path = (environ.get("PATH_INFO") or "/").encode("latin-1").decode("utf-8", "replace")
    if len(path) > 1 and path.endswith("/"):
        path = path.rstrip("/")
    correlation_id = accept_correlation_id(environ.get("HTTP_X_CORRELATION_ID"))

    with correlation(correlation_id):
        status, body, route = handle(method, path, environ, correlation_id)
        seconds = time.monotonic() - started
        route_path = route["path"] if route else "unmatched"
        _record(method, route_path, status, seconds)
        quiet = route_path in ("/health", "/ready", "/metrics")
        log.log(
            "debug" if quiet else ("warn" if status >= 500 else "info"),
            "запрос обработан",
            component="http",
            method=method,
            route=route_path,
            status=status,
            durationMs=round(seconds * 1000, 1),
        )

    if isinstance(body, str):
        payload, content_type = body.encode(), "text/plain; version=0.0.4; charset=utf-8"
    else:
        payload, content_type = encode_json(body), "application/json; charset=utf-8"
    start_response(
        f"{status} {STATUS_TEXT.get(status, 'Unknown')}",
        [
            ("Content-Type", content_type),
            ("Content-Length", str(len(payload))),
            ("x-correlation-id", correlation_id),
        ],
    )
    return [payload]
