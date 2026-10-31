package ru.mirea.delivery.app;

import ru.mirea.delivery.common.JsDates;
import ru.mirea.delivery.domain.GeoPoint;
import ru.mirea.delivery.error.ValidationException;

import java.time.Instant;
import java.util.Map;

/**
 * Запрос курьера от оркестратора саги ({@code RequestCourierRequest} из
 * контрактов). Разбирается и проверяется при создании: дальше сценарий
 * работает с уже проверенными полями.
 */
public record CourierRequest(
        String orderId,
        String restaurantId,
        String customerId,
        Integer orderNumber,
        String idempotencyKey,
        Object pickup,
        Object dropoff,
        Instant readyAt,
        String dropoffAddress,
        String contactPhone) {

    public static CourierRequest from(Object body) {
        Map<?, ?> map = body instanceof Map<?, ?> value ? value : Map.of();
        if (!Ids.isUuid(map.get("orderId"))) {
            throw new ValidationException("orderId: нужен идентификатор заказа");
        }
        if (!(map.get("idempotencyKey") instanceof String key) || key.isBlank()) {
            throw new ValidationException("idempotencyKey: нужен ключ идемпотентности");
        }
        if (!truthy(map.get("pickup")) || !truthy(map.get("dropoff"))) {
            throw new ValidationException("нужны точки выдачи и доставки");
        }
        return new CourierRequest(
                (String) map.get("orderId"),
                text(map.get("restaurantId")),
                text(map.get("customerId")),
                map.get("orderNumber") instanceof Number number ? number.intValue() : null,
                key,
                map.get("pickup"),
                map.get("dropoff"),
                JsDates.parse(map.get("readyAt")),
                cut(map.get("dropoffAddress"), 500),
                cut(map.get("contactPhone"), 32));
    }

    /** Точка выдачи — проверяется при открытии доставки (422 при неверных координатах). */
    public GeoPoint pickupPoint() {
        return point(pickup);
    }

    public GeoPoint dropoffPoint() {
        return point(dropoff);
    }

    private static GeoPoint point(Object value) {
        Map<?, ?> map = value instanceof Map<?, ?> m ? m : Map.of();
        return GeoPoint.of(map.get("latitude"), map.get("longitude"));
    }

    private static boolean truthy(Object value) {
        return value != null && !Boolean.FALSE.equals(value) && !"".equals(value);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String cut(Object value, int limit) {
        if (!(value instanceof String text)) {
            return null;
        }
        return text.length() > limit ? text.substring(0, limit) : text;
    }
}
