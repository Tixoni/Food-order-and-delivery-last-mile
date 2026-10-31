"""
Ошибки приложения.

Без классов: ошибка — обычное исключение `Exception`, к которому
приложен словарь `app` с кодом HTTP, кодом ошибки и подробностями.
Формат ответа тот же, что у TS-версии: шлюз и `order` разбирают его
одинаково для всех сервисов.

Правила предметной области («доставил» раньше «забрал») — 422,
опоздание и гонки («предложение уже закрыто») — 409.
"""


def app_error(status, code, message, details=None):
    error = Exception(message)
    error.app = {
        "status": status,
        "code": code,
        "message": message,
        "details": details or {},
    }
    return error


def app_info(error):
    """Сведения об ошибке приложения или `None`, если это сбой."""
    return getattr(error, "app", None)


def validation(message, details=None):
    return app_error(400, "VALIDATION_FAILED", message, details)


def unauthorized(message, details=None):
    return app_error(401, "UNAUTHORIZED", message, details)


def forbidden(message, details=None):
    return app_error(403, "FORBIDDEN", message, details)


def not_found(message, details=None):
    return app_error(404, "NOT_FOUND", message, details)


def conflict(message, details=None):
    return app_error(409, "CONFLICT", message, details)


def rule(message, details=None):
    return app_error(422, "DOMAIN_RULE_VIOLATED", message, details)


def external(message, details=None):
    return app_error(502, "EXTERNAL_SERVICE_FAILED", message, details)


def duplicate_delivery(order_id):
    """Одновременный запрос по тому же заказу успел создать доставку первым."""
    error = Exception(f"доставка заказа {order_id} уже создана")
    error.duplicate_order = order_id
    return error


def is_duplicate_delivery(error):
    return getattr(error, "duplicate_order", None) is not None
