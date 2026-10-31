package ru.mirea.delivery.domain;

import ru.mirea.delivery.common.Maps;
import ru.mirea.delivery.error.DomainRuleException;

/** Точка на карте. Создаётся только допустимой: широта ±90, долгота ±180. */
public record GeoPoint(double latitude, double longitude) {
    private static final double EARTH_RADIUS_M = 6_371_000;

    /** Путь по улицам в среднем на 40 % длиннее прямой — нужна, только когда маршрутизатор недоступен. */
    public static final double DETOUR = 1.4;

    public GeoPoint {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            throw new DomainRuleException("координаты вне допустимого диапазона",
                    Maps.of("latitude", latitude, "longitude", longitude));
        }
    }

    /** Точка из значений запроса: годятся только числа (как {@code Number.isFinite} в TS). */
    public static GeoPoint of(Object latitude, Object longitude) {
        if (!isNumber(latitude) || !isNumber(longitude)) {
            throw new DomainRuleException("координаты вне допустимого диапазона",
                    Maps.of("latitude", latitude, "longitude", longitude));
        }
        return new GeoPoint(((Number) latitude).doubleValue(), ((Number) longitude).doubleValue());
    }

    private static boolean isNumber(Object value) {
        return value instanceof Number;
    }

    /** Расстояние по прямой (формула гаверсинусов), метры. */
    public double distanceTo(GeoPoint other) {
        double lat1 = Math.toRadians(latitude);
        double lat2 = Math.toRadians(other.latitude);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(other.longitude - longitude);
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
    }
}
