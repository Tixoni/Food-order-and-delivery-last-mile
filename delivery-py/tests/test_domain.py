"""Правила курьера, доставки и предложения — те же, что проверяет delivery.spec.ts."""

from datetime import datetime, timedelta, timezone

import pytest

from delivery import domain, errors

NOW = datetime(2026, 11, 3, 12, 0, tzinfo=timezone.utc)
PICKUP = {"latitude": 55.75, "longitude": 37.61}
DROPOFF = {"latitude": 55.76, "longitude": 37.62}


def courier(status="offline", user_id="u-1", active=True):
    c = domain.courier_new("c-1", "Курьер", "bike", user_id=user_id)
    c["status"] = status
    c["is_active"] = active
    return c


def delivery(status="PENDING", courier_id=None):
    d = domain.delivery_open(
        delivery_id="d-1", order_id="o-1", restaurant_id=None, customer_id=None,
        pickup=PICKUP, dropoff=DROPOFF, ready_at=None, request_key="k-1",
        correlation_id="corr", now=NOW,
    )
    d["status"] = status
    d["courier_id"] = courier_id
    return d


def status_of(error):
    return errors.app_info(error.value)["status"]


def test_shift_requires_account_and_active_courier():
    with pytest.raises(Exception) as unlinked:
        domain.courier_start_shift(courier(user_id=None))
    assert status_of(unlinked) == 422
    with pytest.raises(Exception) as disabled:
        domain.courier_start_shift(courier(active=False))
    assert status_of(disabled) == 422


def test_shift_is_idempotent():
    c = courier()
    assert domain.courier_start_shift(c) is True
    assert domain.courier_start_shift(c) is False
    assert c["status"] == "available"


def test_cannot_leave_shift_with_order():
    c = courier("busy")
    with pytest.raises(Exception) as busy:
        domain.courier_end_shift(c)
    assert status_of(busy) == 422
    assert c["status"] == "busy"


def test_offer_cycle_of_courier():
    c = courier("available")
    domain.courier_offer(c)
    assert c["status"] == "assigned"
    domain.courier_accept(c, "d-1")
    assert (c["status"], c["current_delivery_id"]) == ("busy", "d-1")
    domain.courier_free(c)
    assert (c["status"], c["current_delivery_id"]) == ("available", None)


def test_only_offered_order_can_be_accepted():
    with pytest.raises(Exception) as error:
        domain.courier_accept(courier("available"), "d-1")
    assert status_of(error) == 422


def test_delivered_only_after_picked_up():
    d = delivery("ASSIGNED", "c-1")
    with pytest.raises(Exception) as early:
        domain.delivery_complete(d, "c-1", NOW)
    assert status_of(early) == 422
    assert domain.delivery_pick_up(d, "c-1", NOW) is True
    assert domain.delivery_pick_up(d, "c-1", NOW) is False
    assert domain.delivery_complete(d, "c-1", NOW) is True
    assert d["status"] == "DELIVERED"


def test_foreign_courier_cannot_touch_delivery():
    with pytest.raises(Exception) as foreign:
        domain.delivery_pick_up(delivery("ASSIGNED", "c-1"), "c-2", NOW)
    assert status_of(foreign) == 409


def test_accepted_delivery_cannot_be_accepted_again():
    d = delivery()
    domain.delivery_accept(d, "c-1", NOW)
    with pytest.raises(Exception) as late:
        domain.delivery_accept(d, "c-2", NOW)
    assert status_of(late) == 409


def test_delivered_order_is_not_cancelled():
    d = delivery("DELIVERED", "c-1")
    assert domain.delivery_cancel(d, NOW) is False
    assert d["status"] == "DELIVERED"


def test_new_request_starts_new_search_round():
    d = delivery()
    domain.delivery_not_found(d, NOW)
    later = NOW + timedelta(minutes=5)
    domain.delivery_requested_again(d, "k-2", later)
    assert (d["request_key"], d["not_found_at"], d["search_started_at"]) == ("k-2", None, later)


def test_expired_offer_cannot_be_accepted():
    offer = domain.offer_make("of-1", "d-1", "c-1", 1, NOW, 90)
    with pytest.raises(Exception) as late:
        domain.offer_respond(offer, "c-1", "ACCEPTED", NOW + timedelta(seconds=90))
    assert status_of(late) == 409
    assert domain.offer_is_open(offer)


def test_offer_answered_once_and_only_by_addressee():
    offer = domain.offer_make("of-1", "d-1", "c-1", 1, NOW, 90)
    with pytest.raises(Exception) as foreign:
        domain.offer_respond(offer, "c-2", "ACCEPTED", NOW)
    assert status_of(foreign) == 409
    domain.offer_respond(offer, "c-1", "REJECTED", NOW)
    with pytest.raises(Exception) as closed:
        domain.offer_respond(offer, "c-1", "ACCEPTED", NOW)
    assert status_of(closed) == 409
    domain.offer_expire(offer, NOW)
    assert offer["response"] == "REJECTED"
