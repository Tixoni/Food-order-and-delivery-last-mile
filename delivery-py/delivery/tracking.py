"""
Позиция курьера для клиента (TRK-2) и учёт курьеров администратором (ADM-2).

SEC-7: позицию видит только клиент этого заказа и оператор. Чужому
клиенту — «не найдено», а не «нет прав»: не подтверждаем, что такой
заказ существует.
"""

import uuid

from . import db, dispatch, domain, errors, geo, positions, routing, store
from .views import courier_view

STAFF = ("operator", "admin")


def courier_position(order_id, user_id, roles):
    if not dispatch.is_uuid(order_id):
        raise errors.not_found("доставка не найдена")
    with db.connection() as conn:
        delivery = store.delivery_of_order(conn, order_id)
    is_staff = any(role in STAFF for role in roles)
    if not delivery or (not is_staff and delivery["customer_id"] != user_id):
        raise errors.not_found("доставка не найдена", {"orderId": order_id})

    # Позиция показывается, пока курьер едет к ресторану или к клиенту.
    visible = bool(delivery["courier_id"]) and delivery["status"] in ("ASSIGNED", "PICKED_UP")
    position = positions.get_position(delivery["courier_id"]) if visible else None
    route = routing.delivery_route(delivery)
    return {
        "orderId": order_id,
        "deliveryId": delivery["id"],
        "status": delivery["status"],
        "courierId": delivery["courier_id"] if visible else None,
        # Нет свежей позиции — клиент увидит «позиция устарела», а не точку часовой давности.
        "position": position,
        "dropoff": delivery["dropoff"],
        "pickup": delivery["pickup"],
        # Путь по дорогам для карты клиента; без маршрутизатора — null.
        "route": route,
    }


def list_couriers():
    with db.connection() as conn:
        return [courier_view(courier) for courier in store.list_couriers(conn)]


def register_courier(payload):
    """ADM-2: курьер заводится администратором и привязывается к учётной записи."""
    payload = payload if isinstance(payload, dict) else {}
    full_name = payload.get("fullName")
    full_name = full_name.strip() if isinstance(full_name, str) else ""
    if not full_name or len(full_name) > 200:
        raise errors.validation("укажите имя курьера (до 200 символов)")
    vehicle_type = payload.get("vehicleType") or "foot"
    if vehicle_type not in geo.VEHICLES:
        raise errors.validation(f"vehicleType: одно из {', '.join(geo.VEHICLES)}")
    user_id = payload.get("userId")
    if user_id and not dispatch.is_uuid(user_id):
        raise errors.validation("userId: идентификатор учётной записи Keycloak")
    phone = payload.get("phone")
    phone = phone.strip() or None if isinstance(phone, str) else None

    courier = domain.courier_new(
        courier_id=str(uuid.uuid4()),
        full_name=full_name,
        vehicle_type=vehicle_type,
        user_id=user_id or None,
        phone=phone,
    )
    with db.transaction() as conn:
        store.insert_courier(conn, courier)
    return courier_view(courier)
