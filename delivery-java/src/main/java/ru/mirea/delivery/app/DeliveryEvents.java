package ru.mirea.delivery.app;

import ru.mirea.delivery.common.CorrelationContext;
import ru.mirea.delivery.common.JsDates;
import ru.mirea.delivery.common.Maps;
import ru.mirea.delivery.domain.Delivery;
import ru.mirea.delivery.port.DomainEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * События доставки — топик {@code delivery.events}, ключ партиции —
 * {@code orderId}. Доставка сообщает факты, переход статуса заказа
 * выполняет {@code order}.
 */
public final class DeliveryEvents {
    public static final String TOPIC = "delivery.events";
    public static final String COURIER_ASSIGNED = "CourierAssigned";
    public static final String COURIER_ACCEPTED = "CourierAccepted";
    public static final String COURIER_NOT_FOUND = "CourierNotFound";
    public static final String ORDER_PICKED_UP = "OrderPickedUp";
    public static final String DELIVERY_COMPLETED = "DeliveryCompleted";

    private DeliveryEvents() {
    }

    private static DomainEvent make(Delivery delivery, String type, Map<String, Object> extra, Instant at) {
        Map<String, Object> payload = Maps.of("orderId", delivery.orderId(), "deliveryId", delivery.id());
        payload.putAll(extra);
        return new DomainEvent(UUID.randomUUID().toString(), type, 1, "Delivery", delivery.id(),
                JsDates.format(at), CorrelationContext.getOrNew(), payload);
    }

    /** Курьеру предложен заказ; он ещё может отказаться (DLV-4). */
    public static DomainEvent courierAssigned(Delivery delivery, String courierId, Instant at) {
        return make(delivery, COURIER_ASSIGNED, Maps.of("courierId", courierId), at);
    }

    public static DomainEvent courierAccepted(Delivery delivery, String courierId, Instant at) {
        return make(delivery, COURIER_ACCEPTED, Maps.of("courierId", courierId), at);
    }

    /** Кандидаты исчерпаны: свободных курьеров рядом нет. */
    public static DomainEvent courierNotFound(Delivery delivery, int attempts, Instant at) {
        return make(delivery, COURIER_NOT_FOUND, Maps.of("attempts", attempts), at);
    }

    public static DomainEvent pickedUp(Delivery delivery, String courierId, Instant at, int etaMinutes) {
        return make(delivery, ORDER_PICKED_UP, Maps.of("courierId", courierId,
                "pickedUpAt", JsDates.format(at), "etaMinutes", etaMinutes), at);
    }

    public static DomainEvent completed(Delivery delivery, String courierId, Instant at) {
        return make(delivery, DELIVERY_COMPLETED, Maps.of("courierId", courierId,
                "deliveredAt", JsDates.format(at)), at);
    }
}
