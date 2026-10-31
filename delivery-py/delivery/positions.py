"""
Актуальная позиция курьера в Redis и рассылка подписчикам заказа (DLV-2, TRK-2).

Позиция живёт 60 секунд без обновлений: курьер, потерявший связь,
«устаревает» сам. Канал `ws:order:{orderId}` слушает шлюз и пересылает
позицию клиенту по WebSocket (PERF-3). Недоступность Redis доставку
не останавливает: координата всё равно попадёт в историю.
"""

import json

import redis

from . import clock, log

_state = {"client": None, "ttl_seconds": 60}


def position_key(courier_id):
    return f"courier:loc:{courier_id}"


def order_channel(order_id):
    return f"ws:order:{order_id}"


def init_positions(redis_config, ttl_seconds):
    _state["ttl_seconds"] = ttl_seconds
    _state["client"] = redis.Redis(
        host=redis_config["host"],
        port=redis_config["port"],
        socket_timeout=0.3,
        socket_connect_timeout=0.3,
        decode_responses=True,
    )


def close_positions():
    if _state["client"] is not None:
        _state["client"].close()
        _state["client"] = None


def save_position(courier_id, position):
    client = _state["client"]
    if client is None:
        return
    fields = {
        "lat": str(position["latitude"]),
        "lon": str(position["longitude"]),
        "at": clock.iso(position["at"]),
    }
    if position.get("accuracy") is not None:
        fields["accuracy"] = str(position["accuracy"])
    try:
        pipe = client.pipeline(transaction=True)
        pipe.hset(position_key(courier_id), mapping=fields)
        pipe.expire(position_key(courier_id), _state["ttl_seconds"])
        pipe.execute()
    except redis.RedisError as error:
        log.warn("позиция не сохранена в кэш", component="positions", error=str(error))


def get_position(courier_id):
    client = _state["client"]
    if client is None:
        return None
    try:
        value = client.hgetall(position_key(courier_id))
    except redis.RedisError:
        return None
    if not value.get("lat"):
        return None
    return {
        "latitude": float(value["lat"]),
        "longitude": float(value["lon"]),
        "at": clock.parse_iso(value["at"]),
        "accuracy": float(value["accuracy"]) if value.get("accuracy") else None,
    }


def publish_position(order_id, payload):
    client = _state["client"]
    if client is None:
        return
    try:
        client.publish(
            order_channel(order_id), json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        )
    except redis.RedisError as error:
        log.warn("позиция не разослана", component="positions", error=str(error))


def readiness():
    client = _state["client"]
    try:
        return {"status": "ok"} if client is not None and client.ping() else {"status": "fail"}
    except redis.RedisError as error:
        return {"status": "fail", "error": str(error)}
