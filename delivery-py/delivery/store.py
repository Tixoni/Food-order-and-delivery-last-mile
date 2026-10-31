"""
Хранилище доставки на PostgreSQL + PostGIS — те же таблицы и запросы,
что у TS-версии (`sql-delivery.repository.ts`).

Подбор (DLV-3) — запрос по пространственному индексу: `ST_DWithin`
отсекает курьеров дальше радиуса, `<->` сортирует от ближнего (KNN по
GiST), `FOR UPDATE … SKIP LOCKED` блокирует выбранного и пропускает
тех, кого прямо сейчас предлагает другой экземпляр сервиса.

Функции с параметром `conn` работают в переданном соединении: внутри
`db.transaction()` — в транзакции, иначе — одиночными запросами.
"""

import json
import uuid

import psycopg
from psycopg.types.json import Jsonb

from . import errors
from .db import execute, fetch_all, fetch_one

COURIER_COLUMNS = """
  c.id, c.user_id, c.full_name, c.phone, c.vehicle_type, c.status,
  c.current_delivery_id, c.is_active, c.last_location_at,
  ST_Y(c.last_location::geometry) AS lat, ST_X(c.last_location::geometry) AS lon"""

DELIVERY_COLUMNS = """
  d.id, d.order_id, d.restaurant_id, d.customer_id, d.order_number, d.dropoff_address,
  d.contact_phone, d.status, d.courier_id, d.ready_at,
  d.request_key, d.correlation_id, d.created_at, d.accepted_at, d.picked_up_at,
  d.delivered_at, d.cancelled_at, d.not_found_at, d.search_started_at, d.version,
  ST_Y(d.pickup_location::geometry) AS pickup_lat, ST_X(d.pickup_location::geometry) AS pickup_lon,
  ST_Y(d.dropoff_location::geometry) AS dropoff_lat, ST_X(d.dropoff_location::geometry) AS dropoff_lon"""

OFFER_COLUMNS = """
  a.id, a.delivery_id, a.courier_id, a.sequence, a.offered_at, a.expires_at,
  a.responded_at, a.response"""


def point_sql(lon, lat):
    return f"ST_SetSRID(ST_MakePoint({lon}, {lat}), 4326)::geography"


# ─────────────────────────── строки → словари ───────────────────────────


def _text(value):
    return None if value is None else str(value)


def _point(lat, lon):
    if lat is None:
        return None
    return {"latitude": float(lat), "longitude": float(lon)}


def to_courier(row):
    return {
        "id": str(row["id"]),
        "user_id": _text(row["user_id"]),
        "full_name": row["full_name"],
        "phone": row["phone"],
        "vehicle_type": row["vehicle_type"],
        "status": row["status"],
        "current_delivery_id": _text(row["current_delivery_id"]),
        "is_active": row["is_active"],
        "last_location": _point(row["lat"], row["lon"]),
        "last_location_at": row["last_location_at"],
    }


def to_delivery(row):
    return {
        "id": str(row["id"]),
        "order_id": str(row["order_id"]),
        "restaurant_id": _text(row["restaurant_id"]),
        "customer_id": _text(row["customer_id"]),
        "order_number": None if row["order_number"] is None else int(row["order_number"]),
        "pickup": _point(row["pickup_lat"], row["pickup_lon"]),
        "dropoff": _point(row["dropoff_lat"], row["dropoff_lon"]),
        "dropoff_address": row["dropoff_address"],
        "contact_phone": row["contact_phone"],
        "status": row["status"],
        "courier_id": _text(row["courier_id"]),
        "ready_at": row["ready_at"],
        "request_key": row["request_key"],
        "correlation_id": row["correlation_id"],
        "created_at": row["created_at"],
        "accepted_at": row["accepted_at"],
        "picked_up_at": row["picked_up_at"],
        "delivered_at": row["delivered_at"],
        "cancelled_at": row["cancelled_at"],
        "not_found_at": row["not_found_at"],
        "search_started_at": row["search_started_at"] or row["created_at"],
        "version": int(row["version"]),
    }


def to_offer(row):
    return {
        "id": str(row["id"]),
        "delivery_id": str(row["delivery_id"]),
        "courier_id": str(row["courier_id"]),
        "sequence": int(row["sequence"]),
        "offered_at": row["offered_at"],
        "expires_at": row["expires_at"],
        "responded_at": row["responded_at"],
        "response": row["response"],
    }


# ─────────────────────────── чтение без блокировок ───────────────────────────


def delivery_by_id(conn, delivery_id, lock=False):
    row = fetch_one(
        conn,
        f"SELECT {DELIVERY_COLUMNS} FROM deliveries d WHERE d.id = %s"
        + (" FOR UPDATE" if lock else ""),
        (delivery_id,),
    )
    return to_delivery(row) if row else None


def dashboard(conn, user_id):
    """Экран курьера: курьер, открытое предложение с доставкой, текущая доставка."""
    row = fetch_one(conn, f"SELECT {COURIER_COLUMNS} FROM couriers c WHERE c.user_id = %s", (user_id,))
    if not row:
        return None
    courier = to_courier(row)
    offer_row = fetch_one(
        conn,
        f"""SELECT {OFFER_COLUMNS} FROM delivery_assignments a
             WHERE a.courier_id = %s AND a.response IS NULL""",
        (courier["id"],),
    )
    offer_delivery = delivery_by_id(conn, str(offer_row["delivery_id"])) if offer_row else None
    current = (
        delivery_by_id(conn, courier["current_delivery_id"])
        if courier["current_delivery_id"]
        else None
    )
    return {
        "courier": courier,
        "offer": (
            {"offer": to_offer(offer_row), "delivery": offer_delivery}
            if offer_row and offer_delivery
            else None
        ),
        "delivery": current,
    }


def delivery_of_order(conn, order_id):
    row = fetch_one(
        conn,
        f"""SELECT {DELIVERY_COLUMNS} FROM deliveries d
             WHERE d.order_id = %s ORDER BY d.created_at DESC LIMIT 1""",
        (order_id,),
    )
    return to_delivery(row) if row else None


def location_context(conn, user_id):
    row = fetch_one(
        conn,
        """SELECT c.id, c.status, d.order_id
             FROM couriers c
             LEFT JOIN deliveries d ON d.id = c.current_delivery_id
            WHERE c.user_id = %s""",
        (user_id,),
    )
    if not row:
        return None
    return {"courier_id": str(row["id"]), "status": row["status"], "order_id": _text(row["order_id"])}


def list_couriers(conn):
    rows = fetch_all(conn, f"SELECT {COURIER_COLUMNS} FROM couriers c ORDER BY c.full_name")
    return [to_courier(row) for row in rows]


def candidates(conn, delivery_id, radius_meters, fresh_seconds, limit):
    """
    Ближайшие кандидаты для ранжирования по маршруту — те же условия,
    что у `nearest_candidate`, но без блокировки: решение принимается
    позже, в транзакции. `None` — доставка не ждёт курьера.
    """
    rows = fetch_all(
        conn,
        """SELECT ST_Y(d.pickup_location::geometry) AS pickup_lat,
                  ST_X(d.pickup_location::geometry) AS pickup_lon,
                  n.id, n.vehicle_type, n.distance,
                  ST_Y(n.last_location::geometry) AS lat, ST_X(n.last_location::geometry) AS lon
             FROM deliveries d
             LEFT JOIN LATERAL (
               SELECT c.id, c.vehicle_type, c.last_location,
                      c.last_location <-> d.pickup_location AS distance
                 FROM couriers c
                WHERE c.status = 'available'
                  AND c.is_active
                  AND c.user_id IS NOT NULL
                  AND c.last_location_at > now() - make_interval(secs => %(fresh)s)
                  AND ST_DWithin(c.last_location, d.pickup_location, %(radius)s)
                  AND NOT EXISTS (
                        SELECT 1 FROM delivery_assignments a
                         WHERE a.delivery_id = d.id AND a.courier_id = c.id
                           AND a.offered_at >= d.search_started_at)
                ORDER BY c.last_location <-> d.pickup_location
                LIMIT %(limit)s) n ON true
            WHERE d.id = %(delivery)s AND d.status = 'PENDING'
            ORDER BY n.distance""",
        {"delivery": delivery_id, "fresh": float(fresh_seconds), "radius": float(radius_meters), "limit": limit},
    )
    if not rows:
        return None
    pickup = _point(rows[0]["pickup_lat"], rows[0]["pickup_lon"])
    found = [
        {
            "courier_id": str(row["id"]),
            "vehicle_type": row["vehicle_type"],
            "position": _point(row["lat"], row["lon"]),
            "distance_meters": float(row["distance"]),
        }
        for row in rows
        if row["id"] is not None
    ]
    return {"pickup": pickup, "candidates": found}


def route_of(conn, delivery_id):
    row = fetch_one(
        conn,
        """SELECT distance_m, ST_AsGeoJSON(path::geometry, 6) AS path
             FROM delivery_routes WHERE delivery_id = %s""",
        (delivery_id,),
    )
    if not row:
        return None
    line = json.loads(row["path"])
    return {
        "distanceMeters": int(row["distance_m"]),
        "path": [{"latitude": lat, "longitude": lon} for lon, lat in line["coordinates"]],
    }


def save_route(conn, delivery_id, route):
    # Линии нужны хотя бы две точки; у маршрута «в ту же точку» она одна.
    path = route["path"]
    if len(path) == 1:
        path = [path[0], path[0]]
    if len(path) < 2:
        return
    geometry = {
        "type": "LineString",
        "coordinates": [[point["longitude"], point["latitude"]] for point in path],
    }
    execute(
        conn,
        """INSERT INTO delivery_routes (delivery_id, distance_m, path)
           VALUES (%s, %s, ST_SetSRID(ST_GeomFromGeoJSON(%s), 4326)::geography)
           ON CONFLICT (delivery_id) DO NOTHING""",
        (delivery_id, route["distanceMeters"], json.dumps(geometry)),
    )


# ─────────────────────────── в транзакции ───────────────────────────


def active_delivery_by_order(conn, order_id):
    row = fetch_one(
        conn,
        f"""SELECT {DELIVERY_COLUMNS} FROM deliveries d
             WHERE d.order_id = %s AND d.status NOT IN ('CANCELLED', 'DELIVERED')
             FOR UPDATE""",
        (order_id,),
    )
    return to_delivery(row) if row else None


def insert_delivery(conn, delivery):
    d = delivery
    try:
        execute(
            conn,
            f"""INSERT INTO deliveries
                  (id, order_id, restaurant_id, customer_id, pickup_location, dropoff_location,
                   status, ready_at, request_key, correlation_id, created_at,
                   order_number, dropoff_address, contact_phone, search_started_at)
                VALUES (%s, %s, %s, %s, {point_sql("%s", "%s")}, {point_sql("%s", "%s")},
                        %s, %s, %s, %s, %s, %s, %s, %s, %s)""",
            (
                d["id"], d["order_id"], d["restaurant_id"], d["customer_id"],
                d["pickup"]["longitude"], d["pickup"]["latitude"],
                d["dropoff"]["longitude"], d["dropoff"]["latitude"],
                d["status"], d["ready_at"], d["request_key"], d["correlation_id"],
                d["created_at"], d["order_number"], d["dropoff_address"],
                d["contact_phone"], d["search_started_at"],
            ),
        )
    except psycopg.errors.UniqueViolation as error:
        # ux_deliveries_order_active: одновременный запрос успел первым
        raise errors.duplicate_delivery(d["order_id"]) from error


def save_delivery(conn, delivery):
    d = delivery
    execute(
        conn,
        """UPDATE deliveries
              SET status = %s, courier_id = %s, request_key = %s, not_found_at = %s,
                  accepted_at = %s, assigned_at = COALESCE(assigned_at, %s), picked_up_at = %s,
                  delivered_at = %s, cancelled_at = %s, search_started_at = %s,
                  version = version + 1
            WHERE id = %s""",
        (
            d["status"], d["courier_id"], d["request_key"], d["not_found_at"],
            d["accepted_at"], d["accepted_at"], d["picked_up_at"], d["delivered_at"],
            d["cancelled_at"], d["search_started_at"], d["id"],
        ),
    )


def courier_by_id(conn, courier_id):
    row = fetch_one(
        conn, f"SELECT {COURIER_COLUMNS} FROM couriers c WHERE c.id = %s FOR UPDATE", (courier_id,)
    )
    return to_courier(row) if row else None


def courier_by_user(conn, user_id):
    row = fetch_one(
        conn, f"SELECT {COURIER_COLUMNS} FROM couriers c WHERE c.user_id = %s FOR UPDATE", (user_id,)
    )
    return to_courier(row) if row else None


def insert_courier(conn, courier):
    c = courier
    execute(
        conn,
        """INSERT INTO couriers (id, user_id, full_name, phone, vehicle_type, status, is_active)
           VALUES (%s, %s, %s, %s, %s, %s, %s)""",
        (c["id"], c["user_id"], c["full_name"], c["phone"], c["vehicle_type"], c["status"], c["is_active"]),
    )


def save_courier(conn, courier):
    execute(
        conn,
        "UPDATE couriers SET status = %s, current_delivery_id = %s, is_active = %s WHERE id = %s",
        (courier["status"], courier["current_delivery_id"], courier["is_active"], courier["id"]),
    )


def open_shift(conn, courier_id, at):
    execute(
        conn,
        """INSERT INTO courier_shifts (courier_id, started_at) VALUES (%s, %s)
           ON CONFLICT (courier_id) WHERE ended_at IS NULL DO NOTHING""",
        (courier_id, at),
    )


def close_shift(conn, courier_id, at):
    execute(
        conn,
        """UPDATE courier_shifts SET ended_at = GREATEST(%s, started_at + interval '1 millisecond')
            WHERE courier_id = %s AND ended_at IS NULL""",
        (at, courier_id),
    )


def nearest_candidate(conn, delivery, radius_meters, fresh_seconds, preferred=None):
    """
    Ближайший свободный курьер с блокировкой его строки.

    Два шага, как в TS-версии: сначала десять ближайших (MATERIALIZED не
    даёт планировщику склеить шаги), затем блокировка строки курьера по
    первичному ключу с перепроверкой статуса. Порядок из маршрутизатора
    (`preferred`) применяется к тем же десяти; кого в нём нет — после,
    по прямой.
    """
    pickup = delivery["pickup"]
    point = point_sql("%(lon)s", "%(lat)s")
    row = fetch_one(
        conn,
        f"""WITH nearest AS MATERIALIZED (
              SELECT c.id, c.last_location <-> {point} AS distance
                FROM couriers c
               WHERE c.status = 'available'
                 AND c.is_active
                 AND c.user_id IS NOT NULL
                 AND c.last_location_at > now() - make_interval(secs => %(fresh)s)
                 AND ST_DWithin(c.last_location, {point}, %(radius)s)
                 AND NOT EXISTS (
                       SELECT 1 FROM delivery_assignments a
                        WHERE a.delivery_id = %(delivery)s AND a.courier_id = c.id
                          AND a.offered_at >= %(round)s)
               ORDER BY c.last_location <-> {point}
               LIMIT 10)
            SELECT {COURIER_COLUMNS}, n.distance
              FROM nearest n
              JOIN couriers c ON c.id = n.id
             WHERE c.status = 'available'
             ORDER BY COALESCE(array_position(%(preferred)s::uuid[], n.id), 2147483647), n.distance
             LIMIT 1
             FOR UPDATE OF c SKIP LOCKED""",
        {
            "lon": pickup["longitude"],
            "lat": pickup["latitude"],
            "fresh": float(fresh_seconds),
            "radius": float(radius_meters),
            "delivery": delivery["id"],
            "round": delivery["search_started_at"],
            "preferred": [uuid.UUID(courier_id) for courier_id in preferred] if preferred else None,
        },
    )
    if not row:
        return None
    return {"courier": to_courier(row), "distance_meters": float(row["distance"])}


def open_offer_of(conn, delivery_id):
    row = fetch_one(
        conn,
        f"""SELECT {OFFER_COLUMNS} FROM delivery_assignments a
             WHERE a.delivery_id = %s AND a.response IS NULL FOR UPDATE""",
        (delivery_id,),
    )
    return to_offer(row) if row else None


def offer_by_id(conn, offer_id):
    row = fetch_one(
        conn, f"SELECT {OFFER_COLUMNS} FROM delivery_assignments a WHERE a.id = %s FOR UPDATE", (offer_id,)
    )
    return to_offer(row) if row else None


def offers_count(conn, delivery_id):
    row = fetch_one(
        conn, "SELECT count(*) AS count FROM delivery_assignments WHERE delivery_id = %s", (delivery_id,)
    )
    return int(row["count"])


def insert_offer(conn, offer):
    o = offer
    execute(
        conn,
        """INSERT INTO delivery_assignments (id, delivery_id, courier_id, sequence, offered_at, expires_at)
           VALUES (%s, %s, %s, %s, %s, %s)""",
        (o["id"], o["delivery_id"], o["courier_id"], o["sequence"], o["offered_at"], o["expires_at"]),
    )


def save_offer(conn, offer):
    execute(
        conn,
        "UPDATE delivery_assignments SET response = %s, responded_at = %s WHERE id = %s",
        (offer["response"], offer["responded_at"], offer["id"]),
    )


def due_offers(conn, now, limit):
    rows = fetch_all(
        conn,
        f"""SELECT {OFFER_COLUMNS} FROM delivery_assignments a
             WHERE a.response IS NULL AND a.expires_at <= %s
             ORDER BY a.expires_at
             LIMIT %s
             FOR UPDATE SKIP LOCKED""",
        (now, limit),
    )
    return [to_offer(row) for row in rows]


def enqueue(conn, events, order_id):
    """Transactional Outbox (REL-3): событие — в той же транзакции, что изменение данных."""
    for event in events:
        headers = {
            # События одного заказа — в одну партицию, по порядку.
            "partitionKey": order_id,
            "correlationId": event["correlationId"],
        }
        if event.get("causationId"):
            headers["causationId"] = event["causationId"]
        execute(
            conn,
            """INSERT INTO outbox_messages
                 (aggregate_type, aggregate_id, event_type, event_version, payload, headers)
               VALUES (%s, %s, %s, %s, %s, %s)""",
            (
                event["aggregateType"], event["aggregateId"], event["eventType"],
                event["eventVersion"], Jsonb(event), Jsonb(headers),
            ),
        )
