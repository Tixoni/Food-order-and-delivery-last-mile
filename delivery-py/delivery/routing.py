"""
Маршруты для подбора и для экранов (DLV-3, DLV-6) — маршрутизатор OSRM
за размыкателем цепи (REL-2).

Сервис без маршрутизатора работает: подбор идёт по прямой, экран получает
доставку без линии маршрута, прогноз считается по прямой с поправкой.
Поэтому любой отказ здесь гасится и не доходит до курьера или клиента.

Размыкатель: три отказа подряд (сеть, таймаут, ответ 5xx) размыкают цепь
на 15 с — подбор в это время не ждёт таймаута на каждом заказе. Ответ
«дороги нет» — штатный, а не отказ.
"""

import json
import math
import threading
import time
import urllib.error
import urllib.request

from . import db, errors, geo, log, store

# Сколько ближайших по прямой кандидатов ранжируется по маршруту.
RANKED = 10
FAILURE_THRESHOLD = 3
OPEN_SECONDS = 15
NO_ROUTE = {"NoRoute", "NoSegment", "NoTable"}

_state = {"url": None, "timeout_s": 0.5}
_breaker = {"state": "closed", "failures": 0, "opened_until": 0.0}
_breaker_lock = threading.Lock()


def init_routing(routing_config):
    url = routing_config["url"]
    _state["url"] = url.rstrip("/") if url else None
    _state["timeout_s"] = routing_config["timeout_ms"] / 1000
    _breaker.update({"state": "closed", "failures": 0, "opened_until": 0.0})


def _coordinate(point):
    return f"{point['longitude']:.6f},{point['latitude']:.6f}"


# ─────────────────────────── размыкатель цепи ───────────────────────────


def _breaker_allows():
    with _breaker_lock:
        if _breaker["state"] == "open":
            if time.monotonic() < _breaker["opened_until"]:
                return False
            _breaker["state"] = "half-open"
        return True


def _breaker_success():
    with _breaker_lock:
        if _breaker["state"] != "closed":
            log.info("маршрутизатор: цепь closed", component="routing")
        _breaker.update({"state": "closed", "failures": 0})


def _breaker_failure():
    with _breaker_lock:
        _breaker["failures"] += 1
        if _breaker["state"] == "half-open" or _breaker["failures"] >= FAILURE_THRESHOLD:
            _breaker["state"] = "open"
            _breaker["opened_until"] = time.monotonic() + OPEN_SECONDS
            log.warn("маршрутизатор: цепь open", component="routing")


def _call(path):
    """Ответ OSRM; `None` — дороги нет. Отказ — исключение `external`."""
    if not _state["url"]:
        raise errors.external("маршрутизатор не настроен", {"dependency": "osrm"})
    if not _breaker_allows():
        raise errors.external("маршрутизатор: цепь разомкнута", {"dependency": "osrm"})
    try:
        with urllib.request.urlopen(_state["url"] + path, timeout=_state["timeout_s"]) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read()
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        _breaker_failure()
        raise errors.external(
            "маршрутизатор не ответил", {"dependency": "osrm", "reason": type(error).__name__}
        ) from error
    if status >= 500:
        _breaker_failure()
        raise errors.external(f"маршрутизатор ответил {status}", {"dependency": "osrm"})
    _breaker_success()
    try:
        body = json.loads(raw)
    except ValueError:
        body = {}
    if body.get("code") == "Ok":
        return body
    if body.get("code") in NO_ROUTE:
        return None
    # Ошибка запроса — наша, а не движка: цепь из-за неё не размыкается.
    raise RuntimeError(f"маршрутизатор отклонил запрос: {body.get('code', status)}")


def road_distances(sources, target):
    """Длина пути по дорогам от каждой точки до `target`, метры; `None` — дороги нет."""
    if not sources:
        return []
    # Точка 0 — цель, остальные — источники: одна строка матрицы на курьера.
    points = ";".join(_coordinate(point) for point in [target, *sources])
    indexes = ";".join(str(index + 1) for index in range(len(sources)))
    body = _call(f"/table/v1/driving/{points}?sources={indexes}&destinations=0&annotations=distance")
    distances = (body or {}).get("distances") or []
    result = []
    for index in range(len(sources)):
        row = distances[index] if index < len(distances) else None
        value = row[0] if row else None
        result.append(value if isinstance(value, (int, float)) else None)
    return result


def plan_route(origin, target):
    """Маршрут между двумя точками; `None` — дороги нет."""
    body = _call(
        f"/route/v1/driving/{_coordinate(origin)};{_coordinate(target)}"
        "?overview=simplified&geometries=geojson&alternatives=false&steps=false"
    )
    routes = (body or {}).get("routes") or []
    if not routes:
        return None
    found = routes[0]
    return {
        "distanceMeters": round(found["distance"]),
        "path": [
            {"latitude": lat, "longitude": lon} for lon, lat in found["geometry"]["coordinates"]
        ],
    }


# ─────────────────────────── сценарии ───────────────────────────


def rank_candidates(delivery_id, settings):
    """
    Порядок кандидатов по времени в пути до ресторана — по дорогам и
    с учётом транспорта. Кандидаты по-прежнему отбирает индекс (радиус,
    десять ближайших), маршрутизатор только меняет порядок.

    `None` — ранжировать нечего или маршрутизатор недоступен: тогда
    предложение получает ближайший по прямой.
    """
    with db.connection() as conn:
        found = store.candidates(
            conn, delivery_id, settings["radius_meters"], settings["fresh_seconds"], RANKED
        )
    if not found or len(found["candidates"]) < 2:
        return None
    try:
        roads = road_distances([c["position"] for c in found["candidates"]], found["pickup"])
    except Exception:  # noqa: BLE001 — REL-2: отказ маршрутизатора гасится
        return None

    ranked = []
    # strict=False осознанно: если маршрутизатор вернёт меньше строк, лишние кандидаты
    # не ранжируются и идут после, по прямой (REL-2), а не роняют подбор.
    for candidate, road in zip(found["candidates"], roads, strict=False):
        # Без дороги до ресторана — в конец очереди, но не из подбора.
        seconds = math.inf if road is None else geo.travel_seconds(road, candidate["vehicle_type"])
        ranked.append((seconds, candidate["distance_meters"], candidate["courier_id"]))
    ranked.sort(key=lambda item: (item[0], item[1]))
    return [courier_id for _, _, courier_id in ranked]


def delivery_route(delivery):
    """
    Маршрут «ресторан → клиент»: сохранённый, а если его ещё нет — посчитать
    и сохранить. Если маршрутизатор недоступен, следующий просмотр попробует снова.
    """
    with db.connection() as conn:
        stored = store.route_of(conn, delivery["id"])
    if stored or delivery["status"] in ("DELIVERED", "CANCELLED"):
        return stored
    try:
        route = plan_route(delivery["pickup"], delivery["dropoff"])
        if route:
            with db.connection() as conn:
                store.save_route(conn, delivery["id"], route)
        return route
    except Exception:  # noqa: BLE001 — REL-2
        return None


def stored_route(delivery_id):
    """Только сохранённый маршрут — без обращения к маршрутизатору."""
    with db.connection() as conn:
        return store.route_of(conn, delivery_id)
