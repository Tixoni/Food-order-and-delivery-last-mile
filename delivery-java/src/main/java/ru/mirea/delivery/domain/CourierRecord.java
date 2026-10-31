package ru.mirea.delivery.domain;

import java.time.Instant;

/** Сохраняемое состояние курьера — то, что лежит в строке таблицы {@code couriers}. */
public record CourierRecord(
        String id,
        String userId,
        String fullName,
        String phone,
        VehicleType vehicleType,
        CourierStatus status,
        String currentDeliveryId,
        boolean active,
        GeoPoint lastLocation,
        Instant lastLocationAt) {
}
