"""
Пакетная запись истории координат.

Координаты копятся в памяти и раз в несколько секунд уходят в базу
одним запросом: транзакция на каждую координату положила бы базу.
Тем же проходом обновляется последняя позиция курьера в `couriers` —
по ней идёт подбор (DLV-3). При падении процесса теряются координаты
последних секунд — для истории это допустимо.
"""

import threading
import uuid

from . import db, log

_state = {"buffer": [], "max_buffer": 10_000}
_buffer_lock = threading.Lock()
_flush_lock = threading.Lock()


def init_history(max_buffer):
    _state["max_buffer"] = max_buffer


def add_location(courier_id, position):
    with _buffer_lock:
        if len(_state["buffer"]) >= _state["max_buffer"]:
            # База недоступна долго: старые точки уступают место новым.
            _state["buffer"].pop(0)
        _state["buffer"].append((courier_id, position))


def flush():
    """Записать накопленное. Возвращает число записанных точек."""
    if not _flush_lock.acquire(blocking=False):
        return 0
    try:
        with _buffer_lock:
            batch = _state["buffer"]
            _state["buffer"] = []
        if not batch:
            return 0
        try:
            _write(batch)
            return len(batch)
        except Exception as error:  # noqa: BLE001 — повтор на следующем шаге
            with _buffer_lock:
                _state["buffer"] = (batch + _state["buffer"])[-_state["max_buffer"]:]
            log.warn(
                "история координат не записана, повтор на следующем шаге",
                component="location-history",
                points=len(batch),
                error=str(error),
            )
            return 0
    finally:
        _flush_lock.release()


def _write(batch):
    # Последняя позиция каждого курьера из пачки — для подбора.
    latest = {}
    for courier_id, position in batch:
        latest[courier_id] = position
    with db.transaction() as conn:
        db.execute(
            conn,
            """INSERT INTO courier_locations (courier_id, location, speed, accuracy, recorded_at)
               SELECT u.courier_id,
                      ST_SetSRID(ST_MakePoint(u.lon, u.lat), 4326)::geography,
                      u.speed, u.accuracy, u.recorded_at
                 FROM unnest(%s::uuid[], %s::float8[], %s::float8[], %s::numeric[], %s::numeric[],
                             %s::timestamptz[])
                   AS u(courier_id, lat, lon, speed, accuracy, recorded_at)""",
            (
                [uuid.UUID(courier_id) for courier_id, _ in batch],
                [float(p["latitude"]) for _, p in batch],
                [float(p["longitude"]) for _, p in batch],
                [p.get("speed") for _, p in batch],
                [p.get("accuracy") for _, p in batch],
                [p["at"] for _, p in batch],
            ),
        )
        db.execute(
            conn,
            """UPDATE couriers c
                  SET last_location = ST_SetSRID(ST_MakePoint(u.lon, u.lat), 4326)::geography,
                      last_location_at = u.recorded_at
                 FROM unnest(%s::uuid[], %s::float8[], %s::float8[], %s::timestamptz[])
                   AS u(courier_id, lat, lon, recorded_at)
                WHERE c.id = u.courier_id
                  AND (c.last_location_at IS NULL OR c.last_location_at < u.recorded_at)""",
            (
                [uuid.UUID(courier_id) for courier_id in latest],
                [float(p["latitude"]) for p in latest.values()],
                [float(p["longitude"]) for p in latest.values()],
                [p["at"] for p in latest.values()],
            ),
        )
