package ru.mirea.delivery.domain;

import java.time.Instant;

/** Сохраняемое состояние предложения — строка таблицы {@code delivery_assignments}. */
public record OfferRecord(
        String id,
        String deliveryId,
        String courierId,
        int sequence,
        Instant offeredAt,
        Instant expiresAt,
        Instant respondedAt,
        OfferResponse response) {
}
