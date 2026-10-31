package ru.mirea.delivery.port;

import ru.mirea.delivery.domain.GeoPoint;

import java.util.List;

/** Путь по дорогам: длина и линия для карты (в API — {@code distanceMeters} и {@code path}). */
public record RoadRoute(long distanceMeters, List<GeoPoint> path) {
}
