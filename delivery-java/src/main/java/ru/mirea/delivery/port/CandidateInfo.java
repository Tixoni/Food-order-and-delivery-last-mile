package ru.mirea.delivery.port;

import ru.mirea.delivery.domain.Courier;
import ru.mirea.delivery.domain.GeoPoint;

/**
 * Кандидат для ранжирования по маршруту. Курьер — объект своего вида
 * транспорта: время в пути до ресторана считает он сам.
 */
public record CandidateInfo(Courier courier, GeoPoint position, double distanceMeters) {
}
