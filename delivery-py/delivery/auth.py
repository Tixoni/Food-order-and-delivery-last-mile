"""
Проверка входа и прав (SEC-2) и служебный токен внутреннего API.

Токен пользователя проверяется по открытым ключам Keycloak (JWKS),
которые забираются один раз и держатся в памяти. Проверяются подпись,
срок и получатель. В режиме AUTH_DISABLED (только тесты и локальная
проверка) токен разбирается без проверки подписи — как в TS-версии.
"""

import base64
import hmac
import json

import jwt

from . import errors

ROLES = ("client", "manager", "kitchen", "courier", "operator", "admin")
INTERNAL_TOKEN_HEADER = "x-internal-token"

_state = {"options": None, "jwks": None}


def init_auth(options):
    _state["options"] = options
    _state["jwks"] = None
    if not options["disabled"]:
        base = options["provider_url"] or options["issuer"]
        _state["jwks"] = jwt.PyJWKClient(
            f"{base}/protocol/openid-connect/certs", cache_keys=True, lifespan=600, timeout=5
        )


def extract_bearer(header):
    """Токен — только из заголовка Authorization: строка запроса попадает в журналы целиком."""
    if not isinstance(header, str):
        return None
    parts = header.split(" ")
    if len(parts) >= 2 and parts[0].lower() == "bearer" and parts[1]:
        return parts[1]
    return None


def _decode_without_verification(token):
    parts = token.split(".")
    if len(parts) < 2 or not parts[1]:
        raise errors.unauthorized("токен имеет неверный формат")
    payload = parts[1] + "=" * (-len(parts[1]) % 4)
    try:
        return json.loads(base64.urlsafe_b64decode(payload))
    except (ValueError, json.JSONDecodeError) as error:
        raise errors.unauthorized("токен имеет неверный формат") from error


def _to_user(claims):
    if not isinstance(claims, dict) or not claims.get("sub"):
        raise errors.unauthorized("в токене нет идентификатора пользователя")
    realm_roles = (claims.get("realm_access") or {}).get("roles") or []
    # Неизвестные роли отбрасываются: провайдер выдаёт и служебные роли.
    user = {"id": claims["sub"], "roles": [role for role in realm_roles if role in ROLES]}
    if claims.get("email"):
        user["email"] = claims["email"]
    name = claims.get("name") or claims.get("preferred_username")
    if name:
        user["name"] = name
    if claims.get("restaurantId"):
        user["restaurantId"] = claims["restaurantId"]
    return user


def verify_token(token):
    options = _state["options"]
    if options is None or options["disabled"]:
        return _to_user(_decode_without_verification(token))
    try:
        key = _state["jwks"].get_signing_key_from_jwt(token)
        claims = jwt.decode(
            token,
            key.key,
            algorithms=["RS256"],
            audience=options["audience"],
            issuer=options["issuer"],
            # Часы сервиса и провайдера расходятся на секунды.
            leeway=5,
        )
    except Exception as error:  # noqa: BLE001 — наружу только общая причина
        raise errors.unauthorized("токен недействителен") from error
    return _to_user(claims)


def check_internal_token(presented, expected):
    """Служебный токен `order` → `delivery`; сравнение за постоянное время."""
    presented = presented if isinstance(presented, str) else ""
    if not hmac.compare_digest(presented.encode(), expected.encode()):
        raise errors.unauthorized("внутренний API: неверный служебный токен")
