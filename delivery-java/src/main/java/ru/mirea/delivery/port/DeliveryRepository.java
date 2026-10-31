package ru.mirea.delivery.port;

import ru.mirea.delivery.domain.Courier;
import ru.mirea.delivery.domain.Delivery;

import java.util.List;

/**
 * Хранилище доставки. Чтение без блокировок — здесь; всё, что меняет
 * данные, — внутри {@link #inTransaction}: одна транзакция на сценарий,
 * событие пишется в outbox той же транзакцией (REL-3).
 */
public interface DeliveryRepository {

    /** Работа в одной транзакции: фиксируется при выходе, откатывается при исключении. */
    <T> T inTransaction(TransactionWork<T> work);

    @FunctionalInterface
    interface TransactionWork<T> {
        T run(DeliveryTransaction tx);
    }

    /** Экран курьера: курьер, открытое предложение с доставкой, текущая доставка. */
    CourierDashboard dashboard(String userId);

    /** Последняя доставка заказа — для позиции курьера у клиента. */
    Delivery deliveryOfOrder(String orderId);

    LocationContext locationContext(String userId);

    List<Courier> listCouriers();

    /** Ближайшие кандидаты без блокировки — только для ранжирования по маршруту. */
    Candidates candidates(String deliveryId, int radiusMeters, int freshSeconds, int limit);

    RoadRoute routeOf(String deliveryId);

    void saveRoute(String deliveryId, RoadRoute route);
}
