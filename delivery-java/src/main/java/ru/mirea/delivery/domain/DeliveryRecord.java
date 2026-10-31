package ru.mirea.delivery.domain;

import java.time.Instant;

/** Сохраняемое состояние доставки — строка таблицы {@code deliveries}. */
public record DeliveryRecord(
        String id,
        String orderId,
        String restaurantId,
        String customerId,
        Integer orderNumber,
        GeoPoint pickup,
        GeoPoint dropoff,
        String dropoffAddress,
        String contactPhone,
        DeliveryStatus status,
        String courierId,
        Instant readyAt,
        String requestKey,
        String correlationId,
        Instant createdAt,
        Instant acceptedAt,
        Instant pickedUpAt,
        Instant deliveredAt,
        Instant cancelledAt,
        Instant notFoundAt,
        Instant searchStartedAt,
        int version) {
}
