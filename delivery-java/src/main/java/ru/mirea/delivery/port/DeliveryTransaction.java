package ru.mirea.delivery.port;

import ru.mirea.delivery.domain.Courier;
import ru.mirea.delivery.domain.Delivery;
import ru.mirea.delivery.domain.Offer;

import java.time.Instant;
import java.util.List;

/**
 * Операции внутри транзакции. Чтение здесь блокирует строки
 * ({@code FOR UPDATE}): решение принимается по свежему состоянию, и два
 * экземпляра сервиса не назначат одного курьера дважды.
 */
public interface DeliveryTransaction {
    Delivery deliveryById(String id);

    Delivery activeDeliveryByOrder(String orderId);

    /** @throws DuplicateDeliveryException одновременный запрос по заказу успел первым */
    void insertDelivery(Delivery delivery);

    void saveDelivery(Delivery delivery);

    Courier courierById(String id);

    Courier courierByUser(String userId);

    void insertCourier(Courier courier);

    void saveCourier(Courier courier);

    void openShift(String courierId, Instant at);

    void closeShift(String courierId, Instant at);

    /** Ближайший свободный курьер с блокировкой его строки; порядок {@code preferred} — от маршрутизатора. */
    Candidate nearestCandidate(Delivery delivery, int radiusMeters, int freshSeconds, List<String> preferred);

    Offer openOfferOf(String deliveryId);

    Offer offerById(String id);

    int offersCount(String deliveryId);

    void insertOffer(Offer offer);

    void saveOffer(Offer offer);

    List<Offer> dueOffers(Instant now, int limit);

    /** Transactional Outbox: события — в той же транзакции, что изменение данных. */
    void enqueue(List<DomainEvent> events, String orderId);

    RoadRoute routeOf(String deliveryId);
}
