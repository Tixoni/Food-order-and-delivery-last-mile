"""Координаты, расстояние по прямой и время в пути по типу транспорта."""

import math

from . import errors

EARTH_RADIUS_M = 6_371_000

# Средняя скорость в городе с остановками и пробками, км/ч.
SPEED_KMH = {"foot": 5, "bike": 14, "car": 20}
VEHICLES = ("foot", "bike", "car")

# Путь по улицам в среднем на 40 % длиннее прямой (спайк OSRM по Москве).
# Нужна, только когда маршрутизатор недоступен.
DETOUR = 1.4


def is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def geo_point(latitude, longitude):
    if (
        not is_number(latitude)
        or not is_number(longitude)
        or not math.isfinite(latitude)
        or not math.isfinite(longitude)
        or abs(latitude) > 90
        or abs(longitude) > 180
    ):
        raise errors.rule(
            "координаты вне допустимого диапазона",
            {"latitude": latitude, "longitude": longitude},
        )
    return {"latitude": latitude, "longitude": longitude}


def distance_meters(a, b):
    """Расстояние по прямой (формула гаверсинусов), метры."""
    lat1, lat2 = math.radians(a["latitude"]), math.radians(b["latitude"])
    d_lat = lat2 - lat1
    d_lon = math.radians(b["longitude"] - a["longitude"])
    h = math.sin(d_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(d_lon / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(h))


def travel_seconds(road_meters, vehicle):
    """Время в пути по дорогам, секунды."""
    return road_meters / 1000 / SPEED_KMH[vehicle] * 3600


def eta_minutes(origin, target, vehicle, road_meters=None):
    """Прогноз в минутах (DLV-6): по длине маршрута, без маршрутизатора — по прямой с поправкой."""
    if road_meters is None:
        road_meters = distance_meters(origin, target) * DETOUR
    return max(1, math.ceil(travel_seconds(road_meters, vehicle) / 60))
