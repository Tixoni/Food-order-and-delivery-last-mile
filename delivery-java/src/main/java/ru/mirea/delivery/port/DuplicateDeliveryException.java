package ru.mirea.delivery.port;

/**
 * Одновременный запрос курьера по тому же заказу успел создать доставку
 * первым (уникальный индекс {@code ux_deliveries_order_active}). Сценарий
 * повторяет запрос и отвечает уже созданной доставкой.
 */
public class DuplicateDeliveryException extends RuntimeException {
    public DuplicateDeliveryException(String orderId, Throwable cause) {
        super("доставка заказа " + orderId + " уже создана", cause);
    }
}
