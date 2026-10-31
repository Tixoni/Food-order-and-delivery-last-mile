"""
Предметная область: курьер, доставка, предложение.

Каждая сущность — словарь, каждое правило — функция, которая проверяет
допустимость перехода и меняет словарь. Состояния и переходы те же,
что в TS-версии.

Курьер:
    offline --смена--> available --предложение--> assigned --принял--> busy
    available <-- отказ / молчание -- assigned
    available <-- доставил или заказ отменён -- busy
    offline <-- конец смены -- available

Доставка:
    PENDING --принял--> ASSIGNED --забрал--> PICKED_UP --вручил--> DELIVERED
    любое, кроме DELIVERED, --отмена заказа--> CANCELLED
"""

from datetime import timedelta

from . import errors

# ─────────────────────────── курьер ───────────────────────────


def courier_new(courier_id, full_name, vehicle_type, user_id=None, phone=None):
    return {
        "id": courier_id,
        "user_id": user_id,
        "full_name": full_name,
        "phone": phone,
        "vehicle_type": vehicle_type,
        "status": "offline",
        "current_delivery_id": None,
        "is_active": True,
        "last_location": None,
        "last_location_at": None,
    }


def courier_start_shift(courier):
    """DLV-1. Повторное «на смену» ничего не меняет — `False`."""
    if not courier["is_active"]:
        raise errors.rule("курьер отключён администратором")
    if not courier["user_id"]:
        raise errors.rule("курьер не привязан к учётной записи — смена невозможна")
    if courier["status"] != "offline":
        return False
    courier["status"] = "available"
    return True


def courier_end_shift(courier):
    """Уйти со смены можно только свободным: с заказом на руках — сначала доставка."""
    if courier["status"] == "offline":
        return False
    if courier["status"] != "available":
        raise errors.rule(
            "сначала ответьте на предложение или завершите доставку",
            {"status": courier["status"]},
        )
    courier["status"] = "offline"
    return True


def _courier_require(courier, status, message):
    if courier["status"] != status:
        raise errors.rule(message, {"status": courier["status"]})


def courier_offer(courier):
    _courier_require(courier, "available", "предложить заказ можно только свободному курьеру")
    courier["status"] = "assigned"


def courier_accept(courier, delivery_id):
    _courier_require(courier, "assigned", "принять можно только предложенный заказ")
    courier["status"] = "busy"
    courier["current_delivery_id"] = delivery_id


def courier_decline_offer(courier):
    """Отказ, молчание или отмена заказа до ответа — снова свободен."""
    if courier["status"] == "assigned":
        courier["status"] = "available"


def courier_free(courier):
    """Заказ вручён или отменён — курьер свободен (если не ушёл со смены)."""
    if courier["status"] in ("assigned", "busy"):
        courier["status"] = "available"
    courier["current_delivery_id"] = None


# ─────────────────────────── доставка ───────────────────────────


def delivery_open(
    delivery_id,
    order_id,
    restaurant_id,
    customer_id,
    pickup,
    dropoff,
    ready_at,
    request_key,
    correlation_id,
    now,
    order_number=None,
    dropoff_address=None,
    contact_phone=None,
):
    return {
        "id": delivery_id,
        "order_id": order_id,
        "restaurant_id": restaurant_id,
        "customer_id": customer_id,
        "order_number": order_number,
        "pickup": pickup,
        "dropoff": dropoff,
        "dropoff_address": dropoff_address,
        "contact_phone": contact_phone,
        "status": "PENDING",
        "courier_id": None,
        "ready_at": ready_at,
        "request_key": request_key,
        "correlation_id": correlation_id,
        "created_at": now,
        "accepted_at": None,
        "picked_up_at": None,
        "delivered_at": None,
        "cancelled_at": None,
        "not_found_at": None,
        # Начало текущего раунда поиска: отказавшиеся в этом раунде заказ
        # повторно не получают; новый запрос от order — новый раунд.
        "search_started_at": now,
        "version": 1,
    }


def delivery_is_active(delivery):
    return delivery["status"] not in ("DELIVERED", "CANCELLED")


def delivery_requested_again(delivery, request_key, at):
    """Повторный запрос курьера с новым ключом — после «не найден», новый раунд поиска."""
    delivery["request_key"] = request_key
    delivery["not_found_at"] = None
    delivery["search_started_at"] = at


def delivery_not_found(delivery, at):
    delivery["not_found_at"] = at


def delivery_accept(delivery, courier_id, at):
    if delivery["status"] != "PENDING":
        raise errors.conflict(
            "заказ уже принят другим курьером или отменён", {"status": delivery["status"]}
        )
    delivery["status"] = "ASSIGNED"
    delivery["courier_id"] = courier_id
    delivery["accepted_at"] = at


def _delivery_require_courier(delivery, courier_id):
    if delivery["courier_id"] != courier_id:
        raise errors.conflict("доставка назначена другому курьеру")


def delivery_pick_up(delivery, courier_id, at):
    """DLV-5. Повтор отметки тем же курьером ничего не меняет — `False`."""
    _delivery_require_courier(delivery, courier_id)
    if delivery["status"] == "PICKED_UP":
        return False
    if delivery["status"] != "ASSIGNED":
        raise errors.rule("забрать можно только принятый заказ", {"status": delivery["status"]})
    delivery["status"] = "PICKED_UP"
    delivery["picked_up_at"] = at
    return True


def delivery_complete(delivery, courier_id, at):
    """«Доставил» — только после «забрал»: иначе заказ вручается, не покинув ресторан."""
    _delivery_require_courier(delivery, courier_id)
    if delivery["status"] == "DELIVERED":
        return False
    if delivery["status"] != "PICKED_UP":
        raise errors.rule("сначала отметьте, что забрали заказ", {"status": delivery["status"]})
    delivery["status"] = "DELIVERED"
    delivery["delivered_at"] = at
    return True


def delivery_cancel(delivery, at):
    """Отмена заказа. Вручённый заказ не отменяется — его уже не вернуть."""
    if not delivery_is_active(delivery):
        return False
    delivery["status"] = "CANCELLED"
    delivery["cancelled_at"] = at
    return True


# ─────────────────────────── предложение ───────────────────────────


def offer_make(offer_id, delivery_id, courier_id, sequence, now, ttl_seconds):
    """Предложение заказа курьеру с номером попытки и сроком отклика (DLV-4)."""
    return {
        "id": offer_id,
        "delivery_id": delivery_id,
        "courier_id": courier_id,
        "sequence": sequence,
        "offered_at": now,
        "expires_at": now + timedelta(seconds=ttl_seconds),
        "responded_at": None,
        "response": None,
    }


def offer_is_open(offer):
    return offer["response"] is None


def _offer_close(offer, response, at):
    offer["response"] = response
    offer["responded_at"] = at


def offer_respond(offer, courier_id, response, at):
    """
    Ответ курьера. Принять просроченное нельзя: к этому времени заказ
    уже мог уйти следующему курьеру.
    """
    if offer["courier_id"] != courier_id:
        raise errors.conflict("предложение адресовано другому курьеру")
    if not offer_is_open(offer):
        raise errors.conflict("предложение уже закрыто", {"response": offer["response"]})
    if response == "ACCEPTED" and at >= offer["expires_at"]:
        raise errors.conflict("предложение просрочено: заказ предложен другому курьеру")
    _offer_close(offer, response, at)


def offer_expire(offer, at):
    if offer_is_open(offer):
        _offer_close(offer, "TIMEOUT", at)


def offer_cancel(offer, at):
    if offer_is_open(offer):
        _offer_close(offer, "CANCELLED", at)
