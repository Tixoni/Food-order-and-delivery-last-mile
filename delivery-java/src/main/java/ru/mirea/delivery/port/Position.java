package ru.mirea.delivery.port;

import java.time.Instant;

/** Координата курьера с моментом и (если устройство сообщило) точностью и скоростью. */
public record Position(double latitude, double longitude, Instant at, Double accuracy, Double speed) {
}
