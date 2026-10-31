package ru.mirea.delivery.port;

/**
 * Актуальная позиция курьера и рассылка её подписчикам заказа (DLV-2, TRK-2).
 * Это телеметрия, а не доменные события: в брокер позиция не попадает.
 */
public interface PositionCache {
    /** Позиция живёт недолго — потерявший связь курьер «устаревает» сам. */
    void save(String courierId, Position position);

    /** Свежая позиция или {@code null}. */
    Position get(String courierId);

    /** Рассылка клиенту заказа: канал {@code ws:order:{orderId}}, его слушает шлюз (PERF-3). */
    void publish(String orderId, Object payload);
}
