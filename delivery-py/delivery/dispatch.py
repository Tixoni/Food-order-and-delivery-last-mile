"""
Подбор курьера (DLV-3, DLV-4) — внутренний API для `order` и фоновый подбор.

Триггер подбора один — команда `RequestCourier` от оркестратора саги:
она приходит после подтверждения рестораном, параллельно с готовкой.
Предложения уходят курьерам по очереди, от ближнего к дальнему — по
времени в пути, если маршрутизатор доступен, иначе по прямой. Отказ или
молчание 90 с — следующему; кандидаты кончились — событие
`CourierNotFound`, решение за `order`.
"""

import re
import uuid

from . import clock, config, db, domain, errors, events, geo, routing, store
from .context import correlation, get_correlation_id

UUID_RE = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")


def is_uuid(value):
    return isinstance(value, str) and UUID_RE.fullmatch(value) is not None


def validate_request(request):
    if not is_uuid(request.get("orderId")):
        raise errors.validation("orderId: нужен идентификатор заказа")
    key = request.get("idempotencyKey")
    if not isinstance(key, str) or key.strip() == "":
        raise errors.validation("idempotencyKey: нужен ключ идемпотентности")
    if not request.get("pickup") or not request.get("dropoff"):
        raise errors.validation("нужны точки выдачи и доставки")


def _point_of(value):
    value = value if isinstance(value, dict) else {}
    return geo.geo_point(value.get("latitude"), value.get("longitude"))


def _cut(value, limit):
    return value[:limit] if isinstance(value, str) else None


def request_courier(request, now=None, retried=False):
    """
    Запрос курьера от оркестратора. Тот же ключ — повтор вызова: прежний
    ответ, поиск не перезапускается. Новый ключ — оркестратор повторяет
    запрос после «не найден»: новый раунд поиска.

    `retried` — внутренний флаг: после одновременного запроса вызов
    повторяется один раз, а не до бесконечности.
    """
    now = now or clock.utcnow()
    request = request if isinstance(request, dict) else {}
    validate_request(request)

    try:
        with db.transaction() as conn:
            existing = store.active_delivery_by_order(conn, request["orderId"])
            if existing:
                if existing["request_key"] == request["idempotencyKey"]:
                    delivery, search = existing, False
                else:
                    domain.delivery_requested_again(existing, request["idempotencyKey"], now)
                    store.save_delivery(conn, existing)
                    delivery, search = existing, existing["status"] == "PENDING"
            else:
                delivery = domain.delivery_open(
                    delivery_id=str(uuid.uuid4()),
                    order_id=request["orderId"],
                    restaurant_id=request.get("restaurantId"),
                    customer_id=request.get("customerId"),
                    order_number=request.get("orderNumber"),
                    dropoff_address=_cut(request.get("dropoffAddress"), 500),
                    contact_phone=_cut(request.get("contactPhone"), 32),
                    pickup=_point_of(request["pickup"]),
                    dropoff=_point_of(request["dropoff"]),
                    ready_at=clock.parse_iso(request.get("readyAt")),
                    request_key=request["idempotencyKey"],
                    correlation_id=get_correlation_id(),
                    now=now,
                )
                store.insert_delivery(conn, delivery)
                search = True
    except Exception as error:
        if errors.is_duplicate_delivery(error):
            if retried:
                # Индекс ux_deliveries_order_active держит и вручённую доставку,
                # а поиск действующей её не видит: повтор не поможет, он бы зациклился.
                raise errors.conflict(
                    "по заказу уже есть завершённая доставка", {"orderId": request["orderId"]}
                ) from error
            # Одновременный повтор успел создать доставку — отвечаем ею.
            return request_courier(request, now, retried=True)
        raise

    if search:
        offer_next(delivery["id"], now)
    status = "PENDING" if delivery["status"] == "PENDING" else "ASSIGNED"
    return {"deliveryId": delivery["id"], "status": status}


def release_courier(request, now=None):
    """
    Компенсация шага саги: заказ отменён — доставка закрывается, курьер
    свободен, открытое предложение снимается. Без доставки — `released: false`.
    """
    now = now or clock.utcnow()
    request = request if isinstance(request, dict) else {}
    if not is_uuid(request.get("orderId")):
        raise errors.validation("orderId: нужен идентификатор заказа")
    with db.transaction() as conn:
        delivery = store.active_delivery_by_order(conn, request["orderId"])
        if not delivery or not domain.delivery_cancel(delivery, now):
            return {"released": False}
        store.save_delivery(conn, delivery)

        offer = store.open_offer_of(conn, delivery["id"])
        if offer:
            domain.offer_cancel(offer, now)
            store.save_offer(conn, offer)
        courier_id = delivery["courier_id"] or (offer["courier_id"] if offer else None)
        if courier_id:
            courier = store.courier_by_id(conn, courier_id)
            if courier:
                domain.courier_free(courier)
                store.save_courier(conn, courier)
        return {"released": True}


def offer_next(delivery_id, now=None):
    """
    Предложить заказ следующему кандидату. Безопасно вызывать сколько угодно
    раз и с разных экземпляров: пока открыто предложение или заказ принят,
    вызов ничего не делает. Возвращает "offered", "not-found" или "skipped".
    """
    now = now or clock.utcnow()
    settings = config.dispatch_settings()
    # Порядок по маршруту — до транзакции: блокировки не ждут сетевого ответа.
    preferred = routing.rank_candidates(delivery_id, settings)
    with db.transaction() as conn:
        delivery = store.delivery_by_id(conn, delivery_id, lock=True)
        if not delivery or delivery["status"] != "PENDING":
            return "skipped"
        if store.open_offer_of(conn, delivery["id"]):
            return "skipped"

        with correlation(delivery["correlation_id"] or get_correlation_id()):
            sent = store.offers_count(conn, delivery["id"])
            candidate = store.nearest_candidate(
                conn, delivery, settings["radius_meters"], settings["fresh_seconds"], preferred
            )
            if not candidate:
                # Сообщаем один раз на поиск: повторный вызов до нового
                # запроса от order не плодит одинаковых событий.
                if delivery["not_found_at"]:
                    return "skipped"
                domain.delivery_not_found(delivery, now)
                store.save_delivery(conn, delivery)
                store.enqueue(
                    conn, [events.courier_not_found(delivery, sent, now)], delivery["order_id"]
                )
                return "not-found"

            courier = candidate["courier"]
            offer = domain.offer_make(
                offer_id=str(uuid.uuid4()),
                delivery_id=delivery["id"],
                courier_id=courier["id"],
                sequence=sent + 1,
                now=now,
                ttl_seconds=settings["offer_ttl_seconds"],
            )
            domain.courier_offer(courier)
            store.save_courier(conn, courier)
            store.insert_offer(conn, offer)
            store.enqueue(
                conn, [events.courier_assigned(delivery, courier["id"], now)], delivery["order_id"]
            )
            return "offered"


def expire_offers(now=None, limit=20):
    """
    DLV-4: молчание курьера дольше срока — предложение закрывается, курьер
    возвращается в подбор, заказ уходит следующему. Срок хранится в базе,
    поэтому перезапуск сервиса таймер не теряет.
    """
    now = now or clock.utcnow()
    with db.transaction() as conn:
        due = store.due_offers(conn, now, limit)
        for offer in due:
            domain.offer_expire(offer, now)
            store.save_offer(conn, offer)
            courier = store.courier_by_id(conn, offer["courier_id"])
            if courier:
                domain.courier_decline_offer(courier)
                store.save_courier(conn, courier)
        expired = [offer["delivery_id"] for offer in due]

    for delivery_id in expired:
        offer_next(delivery_id, now)
    return len(expired)
