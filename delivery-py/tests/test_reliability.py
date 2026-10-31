"""
Надёжность без внешних систем: повтор запроса курьера (ошибка №2 журнала),
размыкатель цепи маршрутизатора (REL-2), маскирование журнала (SEC-4).
Базу, OSRM и вывод подменяют заглушки.
"""

import io
import json
import urllib.error
from contextlib import contextmanager

import pytest

from delivery import config, db, dispatch, errors, log, routing, store

ORDER_ID = "11111111-1111-4111-8111-111111111111"
REQUEST = {
    "orderId": ORDER_ID,
    "idempotencyKey": f"{ORDER_ID}:courier:1",
    "pickup": {"latitude": 55.75, "longitude": 37.61},
    "dropoff": {"latitude": 55.76, "longitude": 37.62},
}


@pytest.fixture(autouse=True)
def settings():
    config.apply(config.load_config({"AUTH_DISABLED": "true"}))


@pytest.fixture
def fake_db(monkeypatch):
    @contextmanager
    def transaction():
        yield None

    monkeypatch.setattr(db, "transaction", transaction)


def test_duplicate_after_delivered_is_conflict_not_endless_retry(monkeypatch, fake_db):
    """
    Вручённая доставка держит индекс ux_deliveries_order_active, но поиск
    действующей её не видит — раньше запрос повторялся до RecursionError.
    """
    inserts = []
    monkeypatch.setattr(store, "active_delivery_by_order", lambda conn, order_id: None)

    def insert(conn, delivery):
        inserts.append(delivery["id"])
        raise errors.duplicate_delivery(delivery["order_id"])

    monkeypatch.setattr(store, "insert_delivery", insert)
    with pytest.raises(Exception) as error:
        dispatch.request_courier(REQUEST)
    assert errors.app_info(error.value)["status"] == 409
    assert len(inserts) == 2


def test_concurrent_duplicate_answers_with_existing_delivery(monkeypatch, fake_db):
    """Одновременный запрос успел первым — повтор находит его доставку и отвечает ею."""
    existing = {"id": "d-1", "request_key": REQUEST["idempotencyKey"], "status": "PENDING"}
    found = iter([None, existing])
    monkeypatch.setattr(store, "active_delivery_by_order", lambda conn, order_id: next(found))

    def insert(conn, delivery):
        raise errors.duplicate_delivery(delivery["order_id"])

    monkeypatch.setattr(store, "insert_delivery", insert)
    assert dispatch.request_courier(REQUEST) == {"deliveryId": "d-1", "status": "PENDING"}


def test_breaker_opens_after_three_failures(monkeypatch, capsys):
    calls = []

    def unreachable(url, timeout):
        calls.append(url)
        raise urllib.error.URLError("connection refused")

    monkeypatch.setattr(routing.urllib.request, "urlopen", unreachable)
    routing.init_routing({"url": "http://osrm.local", "timeout_ms": 500})
    point = {"latitude": 55.75, "longitude": 37.61}
    for _ in range(4):
        with pytest.raises(Exception) as error:
            routing.plan_route(point, point)
        assert errors.app_info(error.value)["status"] == 502
    # Четвёртый вызов не ждёт таймаута: цепь разомкнута, в сеть он не идёт.
    assert len(calls) == 3
    assert "цепь open" in capsys.readouterr().out


def test_no_road_is_not_a_failure(monkeypatch):
    @contextmanager
    def no_route(url, timeout):
        yield type("Response", (), {"status": 200, "read": lambda self: b'{"code": "NoRoute"}'})()

    monkeypatch.setattr(routing.urllib.request, "urlopen", no_route)
    routing.init_routing({"url": "http://osrm.local", "timeout_ms": 500})
    point = {"latitude": 55.75, "longitude": 37.61}
    for _ in range(5):
        assert routing.plan_route(point, point) is None
    assert routing._breaker["state"] == "closed"


def test_without_router_ranking_falls_back_to_straight_line(monkeypatch):
    routing.init_routing({"url": None, "timeout_ms": 500})

    @contextmanager
    def connection():
        yield None

    monkeypatch.setattr(db, "connection", connection)
    point = {"latitude": 55.75, "longitude": 37.61}
    monkeypatch.setattr(store, "candidates", lambda *args: {
        "pickup": point,
        "candidates": [
            {"courier_id": "a", "vehicle_type": "bike", "position": point, "distance_meters": 10},
            {"courier_id": "b", "vehicle_type": "car", "position": point, "distance_meters": 20},
        ],
    })
    assert routing.rank_candidates("d-1", config.dispatch_settings()) is None


def test_log_masks_personal_data(monkeypatch):
    out = io.StringIO()
    monkeypatch.setattr(log.sys, "stdout", out)
    log.configure("delivery", "info")
    log.info("заказ", contactPhone="+79990000000", dropoff_address="ул. Тверская, 1",
             nested={"accessToken": "secret"}, orderNumber=7)
    record = json.loads(out.getvalue())
    assert record["contactPhone"] == record["dropoff_address"] == "***"
    assert record["nested"] == {"accessToken": "***"} and record["orderNumber"] == 7
