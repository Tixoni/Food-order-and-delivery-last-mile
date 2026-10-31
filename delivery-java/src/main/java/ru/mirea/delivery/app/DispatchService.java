package ru.mirea.delivery.app;

import ru.mirea.delivery.common.CorrelationContext;
import ru.mirea.delivery.common.Maps;
import ru.mirea.delivery.config.AppConfig;
import ru.mirea.delivery.domain.Courier;
import ru.mirea.delivery.domain.Delivery;
import ru.mirea.delivery.domain.DeliveryStatus;
import ru.mirea.delivery.domain.Offer;
import ru.mirea.delivery.error.ValidationException;
import ru.mirea.delivery.port.Candidate;
import ru.mirea.delivery.port.DeliveryRepository;
import ru.mirea.delivery.port.DuplicateDeliveryException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Подбор курьера (DLV-3, DLV-4) — внутренний API для {@code order} и фоновый подбор.
 *
 * Триггер подбора один — команда «запросить курьера» от оркестратора саги:
 * она приходит после подтверждения рестораном, параллельно с готовкой.
 * Предложения уходят курьерам по очереди, от ближнего к дальнему — по
 * времени в пути, если маршрутизатор доступен, иначе по прямой. Отказ
 * или молчание — следующему; кандидаты кончились — событие
 * {@code CourierNotFound}, решение за {@code order}.
 */
public final class DispatchService {
    private final DeliveryRepository deliveries;
    private final Routing routing;
    private final AppConfig.Dispatch settings;
    private final Clock clock;

    public DispatchService(DeliveryRepository deliveries, Routing routing, AppConfig.Dispatch settings, Clock clock) {
        this.deliveries = deliveries;
        this.routing = routing;
        this.settings = settings;
        this.clock = clock;
    }

    private record Opened(Delivery delivery, boolean search) {
    }

    /**
     * Запрос курьера. Тот же ключ — повтор вызова: прежний ответ, поиск не
     * перезапускается. Новый ключ — оркестратор повторяет запрос после
     * «не найден»: новый раунд поиска.
     */
    public Map<String, Object> requestCourier(Object body) {
        CourierRequest request = CourierRequest.from(body);
        Instant now = clock.instant();
        Opened opened;
        try {
            opened = deliveries.inTransaction(tx -> {
                Delivery existing = tx.activeDeliveryByOrder(request.orderId());
                if (existing != null) {
                    if (request.idempotencyKey().equals(existing.requestKey())) {
                        return new Opened(existing, false);
                    }
                    existing.requestedAgain(request.idempotencyKey(), now);
                    tx.saveDelivery(existing);
                    return new Opened(existing, existing.status() == DeliveryStatus.PENDING);
                }
                Delivery delivery = Delivery.open(UUID.randomUUID().toString(), request.orderId(),
                        request.restaurantId(), request.customerId(), request.orderNumber(),
                        request.pickupPoint(), request.dropoffPoint(), request.dropoffAddress(),
                        request.contactPhone(), request.readyAt(), request.idempotencyKey(),
                        CorrelationContext.getOrNew(), now);
                tx.insertDelivery(delivery);
                return new Opened(delivery, true);
            });
        } catch (DuplicateDeliveryException raced) {
            // Одновременный повтор успел создать доставку — отвечаем ею.
            return requestCourier(body);
        }
        if (opened.search()) {
            offerNext(opened.delivery().id(), now);
        }
        String status = opened.delivery().status() == DeliveryStatus.PENDING ? "PENDING" : "ASSIGNED";
        return Maps.of("deliveryId", opened.delivery().id(), "status", status);
    }

    /**
     * Компенсация шага саги: заказ отменён — доставка закрывается, курьер
     * свободен, открытое предложение снимается. Без доставки — {@code released: false}.
     */
    public Map<String, Object> releaseCourier(Object body) {
        Map<?, ?> map = body instanceof Map<?, ?> value ? value : Map.of();
        if (!Ids.isUuid(map.get("orderId"))) {
            throw new ValidationException("orderId: нужен идентификатор заказа");
        }
        String orderId = (String) map.get("orderId");
        Instant now = clock.instant();
        return deliveries.inTransaction(tx -> {
            Delivery delivery = tx.activeDeliveryByOrder(orderId);
            if (delivery == null || !delivery.cancel(now)) {
                return Maps.of("released", false);
            }
            tx.saveDelivery(delivery);
            Offer offer = tx.openOfferOf(delivery.id());
            if (offer != null) {
                offer.cancel(now);
                tx.saveOffer(offer);
            }
            String courierId = delivery.courierId() != null ? delivery.courierId()
                    : offer != null ? offer.courierId() : null;
            if (courierId != null) {
                Courier courier = tx.courierById(courierId);
                if (courier != null) {
                    courier.free();
                    tx.saveCourier(courier);
                }
            }
            return Maps.of("released", true);
        });
    }

    /**
     * Предложить заказ следующему кандидату. Безопасно вызывать сколько
     * угодно раз и с разных экземпляров: пока открыто предложение или заказ
     * принят, вызов ничего не делает.
     */
    public OfferOutcome offerNext(String deliveryId, Instant now) {
        // Порядок по маршруту — до транзакции: блокировки не ждут сетевого ответа.
        List<String> preferred = routing.rankCandidates(deliveryId, settings);
        return deliveries.inTransaction(tx -> {
            Delivery delivery = tx.deliveryById(deliveryId);
            if (delivery == null || delivery.status() != DeliveryStatus.PENDING) {
                return OfferOutcome.SKIPPED;
            }
            if (tx.openOfferOf(delivery.id()) != null) {
                return OfferOutcome.SKIPPED;
            }
            String correlationId = delivery.correlationId() != null
                    ? delivery.correlationId() : CorrelationContext.getOrNew();
            return CorrelationContext.call(correlationId, () -> {
                int sent = tx.offersCount(delivery.id());
                Candidate candidate = tx.nearestCandidate(delivery, settings.radiusMeters(),
                        settings.freshSeconds(), preferred);
                if (candidate == null) {
                    // Сообщаем один раз на поиск: повтор до нового запроса от order не плодит событий.
                    if (delivery.notFoundAt() != null) {
                        return OfferOutcome.SKIPPED;
                    }
                    delivery.notFound(now);
                    tx.saveDelivery(delivery);
                    tx.enqueue(List.of(DeliveryEvents.courierNotFound(delivery, sent, now)), delivery.orderId());
                    return OfferOutcome.NOT_FOUND;
                }
                Courier courier = candidate.courier();
                Offer offer = Offer.make(UUID.randomUUID().toString(), delivery.id(), courier.id(), sent + 1,
                        now, settings.offerTtlSeconds());
                courier.offer();
                tx.saveCourier(courier);
                tx.insertOffer(offer);
                tx.enqueue(List.of(DeliveryEvents.courierAssigned(delivery, courier.id(), now)), delivery.orderId());
                return OfferOutcome.OFFERED;
            });
        });
    }

    public OfferOutcome offerNext(String deliveryId) {
        return offerNext(deliveryId, clock.instant());
    }

    /**
     * DLV-4: молчание курьера дольше срока — предложение закрывается, курьер
     * возвращается в подбор, заказ уходит следующему. Срок хранится в базе,
     * поэтому перезапуск сервиса таймер не теряет.
     */
    public int expireOffers(int limit) {
        Instant now = clock.instant();
        List<String> expired = deliveries.inTransaction(tx -> {
            List<String> ids = new ArrayList<>();
            for (Offer offer : tx.dueOffers(now, limit)) {
                offer.expire(now);
                tx.saveOffer(offer);
                Courier courier = tx.courierById(offer.courierId());
                if (courier != null) {
                    courier.declineOffer();
                    tx.saveCourier(courier);
                }
                ids.add(offer.deliveryId());
            }
            return ids;
        });
        for (String deliveryId : expired) {
            offerNext(deliveryId, now);
        }
        return expired.size();
    }
}
