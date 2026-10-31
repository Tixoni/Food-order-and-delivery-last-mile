package ru.mirea.delivery.port;

import ru.mirea.delivery.domain.GeoPoint;

import java.util.List;

/**
 * Маршрутизатор (DLV-6) — внешний движок OSRM.
 *
 * Сервис без него работает (REL-2): подбор идёт по прямому расстоянию,
 * прогноз — по прямой с поправкой на улицы. Поэтому отказ здесь —
 * исключение, которое вызывающий гасит, а не причина отказать курьеру.
 */
public interface RoutePlanner {
    /** Длина пути по дорогам от каждой точки до {@code to}, метры; {@code null} — дороги нет. */
    List<Double> roadDistances(List<GeoPoint> from, GeoPoint to);

    /** Маршрут между двумя точками; {@code null} — дороги нет. */
    RoadRoute route(GeoPoint from, GeoPoint to);
}
