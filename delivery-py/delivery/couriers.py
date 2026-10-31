"""
Действия курьера (DLV-1, DLV-2, DLV-4, DLV-5).

Курьер определяется по учётной записи из токена, а не по идентификатору
в запросе: курьер не может отметить чужую доставку или ответить на
чужое предложение, подставив другой номер (SEC-2).
"""

import math

from . import clock, db, dispatch, domain, errors, events, geo, history, positions, routing, store
from .context import correlation
from .views import courier_view, delivery_view


def _not_linked():
    return errors.forbidden(
        "учётная запись не привязана к курьеру — обратитесь к администратору"
    )


def _courier_of(conn, user_id):
    courier = store.courier_by_user(conn, user_id)
    if not courier:
        raise _not_linked()
    return courier


def _delivery_of(conn, delivery_id):
    delivery = store.delivery_by_id(conn, delivery_id, lock=True) if dispatch.is_uuid(delivery_id) else None
    if not delivery:
        raise errors.not_found("доставка не найдена", {"deliveryId": delivery_id})
    return delivery


def _offer_of(conn, offer_id):
    offer = store.offer_by_id(conn, offer_id) if dispatch.is_uuid(offer_id) else None
    if not offer:
        raise errors.not_found("предложение не найдено", {"offerId": offer_id})
    return offer


def _emit(conn, delivery, make_event):
    """Событие — со сквозным идентификатором запроса, начавшего подбор (OBS-2)."""
    if delivery["correlation_id"]:
        with correlation(delivery["correlation_id"]):
            event = make_event()
    else:
        event = make_event()
    store.enqueue(conn, [event], delivery["order_id"])


def dashboard(user_id):
    """Экран курьера: статус, открытое предложение, текущая доставка."""
    with db.connection() as conn:
        view = store.dashboard(conn, user_id)
    if not view:
        raise _not_linked()
    courier = view["courier"]
    # Свежая позиция — из кэша; нет её — последняя известная из базы.
    position = positions.get_position(courier["id"])
    if position is None and courier["last_location"] and courier["last_location_at"]:
        position = {**courier["last_location"], "at": courier["last_location_at"]}
    # Маршрут «ресторан → клиент» — и в предложении: курьер решает, брать ли заказ.
    offer = view["offer"]
    offer_route = routing.delivery_route(offer["delivery"]) if offer else None
    route = routing.delivery_route(view["delivery"]) if view["delivery"] else None
    return {
        "courier": courier_view(courier),
        "position": position,
        "offer": (
            {
                "id": offer["offer"]["id"],
                "sequence": offer["offer"]["sequence"],
                "expiresAt": offer["offer"]["expires_at"],
                "delivery": delivery_view(offer["delivery"], with_contact=False, route=offer_route),
            }
            if offer
            else None
        ),
        "delivery": delivery_view(view["delivery"], route=route) if view["delivery"] else None,
    }


def set_shift(user_id, on_shift, now=None):
    """DLV-1: на смену и со смены."""
    now = now or clock.utcnow()
    with db.transaction() as conn:
        courier = _courier_of(conn, user_id)
        changed = domain.courier_start_shift(courier) if on_shift else domain.courier_end_shift(courier)
        if changed:
            store.save_courier(conn, courier)
            if on_shift:
                store.open_shift(conn, courier["id"], now)
            else:
                store.close_shift(conn, courier["id"], now)
        return courier_view(courier)


def accept(user_id, offer_id, now=None):
    """DLV-4: принять предложение в срок."""
    now = now or clock.utcnow()
    with db.transaction() as conn:
        courier = _courier_of(conn, user_id)
        offer = _offer_of(conn, offer_id)
        domain.offer_respond(offer, courier["id"], "ACCEPTED", now)

        delivery = store.delivery_by_id(conn, offer["delivery_id"], lock=True)
        if not delivery:
            raise errors.not_found("доставка не найдена")
        domain.delivery_accept(delivery, courier["id"], now)
        domain.courier_accept(courier, delivery["id"])

        store.save_offer(conn, offer)
        store.save_delivery(conn, delivery)
        store.save_courier(conn, courier)
        _emit(conn, delivery, lambda: events.courier_accepted(delivery, courier["id"], now))
        return delivery_view(delivery)


def reject(user_id, offer_id, now=None):
    """DLV-4: отказаться — заказ сразу уходит следующему кандидату."""
    now = now or clock.utcnow()
    with db.transaction() as conn:
        courier = _courier_of(conn, user_id)
        offer = _offer_of(conn, offer_id)
        domain.offer_respond(offer, courier["id"], "REJECTED", now)
        domain.courier_decline_offer(courier)
        store.save_offer(conn, offer)
        store.save_courier(conn, courier)
        delivery_id = offer["delivery_id"]
    dispatch.offer_next(delivery_id, now)
    return {"rejected": True}


def picked_up(user_id, delivery_id, now=None):
    """
    DLV-5: забрал заказ в ресторане. Прогноз — от ресторана до клиента по
    маршруту (DLV-6); `order` берёт по нему срок доставки. Маршрут к этому
    времени уже посчитан для экрана курьера — здесь только чтение из базы.
    """
    now = now or clock.utcnow()
    with db.transaction() as conn:
        courier = _courier_of(conn, user_id)
        delivery = _delivery_of(conn, delivery_id)
        if domain.delivery_pick_up(delivery, courier["id"], now):
            store.save_delivery(conn, delivery)
            route = store.route_of(conn, delivery["id"])
            eta = geo.eta_minutes(
                delivery["pickup"],
                delivery["dropoff"],
                courier["vehicle_type"],
                route["distanceMeters"] if route else None,
            )
            _emit(conn, delivery, lambda: events.picked_up(delivery, courier["id"], now, eta))
        return delivery_view(delivery)


def delivered(user_id, delivery_id, now=None):
    """DLV-5: вручил — курьер снова свободен."""
    now = now or clock.utcnow()
    with db.transaction() as conn:
        courier = _courier_of(conn, user_id)
        delivery = _delivery_of(conn, delivery_id)
        if domain.delivery_complete(delivery, courier["id"], now):
            domain.courier_free(courier)
            store.save_delivery(conn, delivery)
            store.save_courier(conn, courier)
            _emit(conn, delivery, lambda: events.completed(delivery, courier["id"], now))
        return delivery_view(delivery)


def _number(value):
    """Число из тела запроса, как `Number(...)` в JS: строка с числом тоже годится."""
    if geo.is_number(value):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value.strip())
        except ValueError:
            return math.nan
    return math.nan


def _measure_or_none(value):
    """
    Точность и скорость от устройства. Вне диапазона колонки истории
    (NUMERIC(6,2), меньше 10 000) — «не измерено»: иначе одна такая точка
    срывала бы запись всей пачки координат.
    """
    if value is None:
        return None
    number = _number(value)
    if not math.isfinite(number):
        return None
    return number if 0 <= number < 10_000 else None


def update_location(user_id, payload, now=None):
    """
    DLV-2: координата курьера. В кэш и подписчикам заказа — сразу (PERF-3),
    в историю — пачкой. Позиция принимается только на смене.
    """
    now = now or clock.utcnow()
    payload = payload if isinstance(payload, dict) else {}
    point = geo.geo_point(_number(payload.get("latitude")), _number(payload.get("longitude")))
    with db.connection() as conn:
        context = store.location_context(conn, user_id)
    if not context:
        raise _not_linked()
    if context["status"] == "offline":
        raise errors.forbidden("позиция принимается только на смене")

    position = {
        **point,
        "at": now,
        "accuracy": _measure_or_none(payload.get("accuracy")),
        "speed": _measure_or_none(payload.get("speed")),
    }
    positions.save_position(context["courier_id"], position)
    history.add_location(context["courier_id"], position)
    if context["order_id"]:
        positions.publish_position(
            context["order_id"],
            {
                "orderId": context["order_id"],
                "courierId": context["courier_id"],
                "latitude": point["latitude"],
                "longitude": point["longitude"],
                "at": clock.iso(now),
            },
        )
    return {"accepted": True}
