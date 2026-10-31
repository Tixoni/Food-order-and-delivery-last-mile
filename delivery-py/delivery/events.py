"""
События доставки — топик `delivery.events`, ключ партиции — `orderId`.

Доставка сообщает факты, переход статуса заказа выполняет `order`.
Конверт события тот же, что у всех сервисов (`packages/shared`):
по `messageId` потребитель отсекает повторы, по `correlationId`
связываются записи одного действия.
"""

import uuid

from . import clock
from .context import get_correlation_id

TOPIC = "delivery.events"

COURIER_ASSIGNED = "CourierAssigned"
COURIER_ACCEPTED = "CourierAccepted"
COURIER_NOT_FOUND = "CourierNotFound"
ORDER_PICKED_UP = "OrderPickedUp"
DELIVERY_COMPLETED = "DeliveryCompleted"


def create_event(event_type, aggregate_type, aggregate_id, payload, correlation_id, occurred_at,
                 event_version=1):
    if not event_type or not aggregate_type or not aggregate_id:
        raise ValueError("eventType, aggregateType и aggregateId обязательны")
    if not correlation_id:
        raise ValueError("correlationId обязателен: без него теряется трассировка")
    return {
        "messageId": str(uuid.uuid4()),
        "eventType": event_type,
        "eventVersion": event_version,
        "aggregateType": aggregate_type,
        "aggregateId": aggregate_id,
        "occurredAt": clock.iso(occurred_at),
        "correlationId": correlation_id,
        "payload": payload,
    }


def _delivery_event(delivery, event_type, payload, at):
    body = {"orderId": delivery["order_id"], "deliveryId": delivery["id"]}
    body.update(payload)
    return create_event(event_type, "Delivery", delivery["id"], body, get_correlation_id(), at)


def courier_assigned(delivery, courier_id, at):
    """Курьеру предложен заказ; он ещё может отказаться (DLV-4)."""
    return _delivery_event(delivery, COURIER_ASSIGNED, {"courierId": courier_id}, at)


def courier_accepted(delivery, courier_id, at):
    return _delivery_event(delivery, COURIER_ACCEPTED, {"courierId": courier_id}, at)


def courier_not_found(delivery, attempts, at):
    """Кандидаты исчерпаны: свободных курьеров рядом нет."""
    return _delivery_event(delivery, COURIER_NOT_FOUND, {"attempts": attempts}, at)


def picked_up(delivery, courier_id, at, eta_minutes):
    return _delivery_event(
        delivery,
        ORDER_PICKED_UP,
        {"courierId": courier_id, "pickedUpAt": clock.iso(at), "etaMinutes": eta_minutes},
        at,
    )


def completed(delivery, courier_id, at):
    return _delivery_event(
        delivery, DELIVERY_COMPLETED, {"courierId": courier_id, "deliveredAt": clock.iso(at)}, at
    )
