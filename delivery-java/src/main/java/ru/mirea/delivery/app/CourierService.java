package ru.mirea.delivery.app;

import ru.mirea.delivery.common.CorrelationContext;
import ru.mirea.delivery.common.JsDates;
import ru.mirea.delivery.common.Maps;
import ru.mirea.delivery.domain.Courier;
import ru.mirea.delivery.domain.CourierStatus;
import ru.mirea.delivery.domain.Delivery;
import ru.mirea.delivery.domain.GeoPoint;
import ru.mirea.delivery.domain.Offer;
import ru.mirea.delivery.domain.OfferResponse;
import ru.mirea.delivery.error.ForbiddenException;
import ru.mirea.delivery.error.NotFoundException;
import ru.mirea.delivery.port.CourierDashboard;
import ru.mirea.delivery.port.DeliveryRepository;
import ru.mirea.delivery.port.DeliveryTransaction;
import ru.mirea.delivery.port.DomainEvent;
import ru.mirea.delivery.port.LocationContext;
import ru.mirea.delivery.port.LocationHistory;
import ru.mirea.delivery.port.Position;
import ru.mirea.delivery.port.PositionCache;
import ru.mirea.delivery.port.RoadRoute;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Действия курьера (DLV-1, DLV-2, DLV-4, DLV-5).
 *
 * Курьер определяется по учётной записи из токена, а не по идентификатору
 * в запросе: курьер не может отметить чужую доставку или ответить на
 * чужое предложение, подставив другой номер (SEC-2).
 */
public final class CourierService {
    private final DeliveryRepository deliveries;
    private final PositionCache positions;
    private final LocationHistory history;
    private final DispatchService dispatch;
    private final Routing routing;
    private final Clock clock;

    public CourierService(DeliveryRepository deliveries, PositionCache positions, LocationHistory history,
                          DispatchService dispatch, Routing routing, Clock clock) {
        this.deliveries = deliveries;
        this.positions = positions;
        this.history = history;
        this.dispatch = dispatch;
        this.routing = routing;
        this.clock = clock;
    }

    private static ForbiddenException notLinked() {
        return new ForbiddenException("учётная запись не привязана к курьеру — обратитесь к администратору");
    }

    /** Экран курьера: статус, открытое предложение, текущая доставка. */
    public Map<String, Object> dashboard(String userId) {
        CourierDashboard view = deliveries.dashboard(userId);
        if (view == null) {
            throw notLinked();
        }
        Courier courier = view.courier();
        // Свежая позиция — из кэша; нет её — последняя известная из базы.
        Map<String, Object> position = Views.position(positions.get(courier.id()), true);
        if (position == null && courier.lastLocation() != null && courier.lastLocationAt() != null) {
            position = Views.position(new Position(courier.lastLocation().latitude(),
                    courier.lastLocation().longitude(), courier.lastLocationAt(), null, null), false);
        }
        // Маршрут «ресторан → клиент» — и в предложении: курьер решает, брать ли заказ.
        Map<String, Object> offer = null;
        if (view.offer() != null) {
            RoadRoute offerRoute = routing.deliveryRoute(view.offerDelivery());
            offer = Maps.of(
                    "id", view.offer().id(),
                    "sequence", view.offer().sequence(),
                    "expiresAt", view.offer().expiresAt(),
                    "delivery", Views.delivery(view.offerDelivery(), false, offerRoute));
        }
        Map<String, Object> current = null;
        if (view.delivery() != null) {
            current = Views.delivery(view.delivery(), true, routing.deliveryRoute(view.delivery()));
        }
        return Maps.of("courier", Views.courier(courier), "position", position, "offer", offer,
                "delivery", current);
    }

    /** DLV-1: на смену и со смены. */
    public Map<String, Object> setShift(String userId, boolean onShift) {
        Instant now = clock.instant();
        return deliveries.inTransaction(tx -> {
            Courier courier = courierOf(tx, userId);
            boolean changed = onShift ? courier.startShift() : courier.endShift();
            if (changed) {
                tx.saveCourier(courier);
                if (onShift) {
                    tx.openShift(courier.id(), now);
                } else {
                    tx.closeShift(courier.id(), now);
                }
            }
            return Views.courier(courier);
        });
    }

    /** DLV-4: принять предложение в срок. */
    public Map<String, Object> accept(String userId, String offerId) {
        Instant now = clock.instant();
        return deliveries.inTransaction(tx -> {
            Courier courier = courierOf(tx, userId);
            Offer offer = offerOf(tx, offerId);
            offer.respond(courier.id(), OfferResponse.ACCEPTED, now);
            Delivery delivery = tx.deliveryById(offer.deliveryId());
            if (delivery == null) {
                throw new NotFoundException("доставка не найдена");
            }
            delivery.accept(courier.id(), now);
            courier.accept(delivery.id());
            tx.saveOffer(offer);
            tx.saveDelivery(delivery);
            tx.saveCourier(courier);
            emit(tx, delivery, () -> DeliveryEvents.courierAccepted(delivery, courier.id(), now));
            return Views.delivery(delivery);
        });
    }

    /** DLV-4: отказаться — заказ сразу уходит следующему кандидату. */
    public Map<String, Object> reject(String userId, String offerId) {
        Instant now = clock.instant();
        String deliveryId = deliveries.inTransaction(tx -> {
            Courier courier = courierOf(tx, userId);
            Offer offer = offerOf(tx, offerId);
            offer.respond(courier.id(), OfferResponse.REJECTED, now);
            courier.declineOffer();
            tx.saveOffer(offer);
            tx.saveCourier(courier);
            return offer.deliveryId();
        });
        dispatch.offerNext(deliveryId, now);
        return Maps.of("rejected", true);
    }

    /**
     * DLV-5: забрал заказ в ресторане. Прогноз — от ресторана до клиента по
     * маршруту (DLV-6); {@code order} берёт по нему срок доставки. Маршрут
     * к этому времени уже посчитан для экрана курьера — здесь только чтение.
     */
    public Map<String, Object> pickedUp(String userId, String deliveryId) {
        Instant now = clock.instant();
        return deliveries.inTransaction(tx -> {
            Courier courier = courierOf(tx, userId);
            Delivery delivery = deliveryOf(tx, deliveryId);
            if (delivery.pickUp(courier.id(), now)) {
                tx.saveDelivery(delivery);
                RoadRoute route = tx.routeOf(delivery.id());
                int eta = courier.etaMinutes(delivery.pickup(), delivery.dropoff(),
                        route != null ? (double) route.distanceMeters() : null);
                emit(tx, delivery, () -> DeliveryEvents.pickedUp(delivery, courier.id(), now, eta));
            }
            return Views.delivery(delivery);
        });
    }

    /** DLV-5: вручил — курьер снова свободен. */
    public Map<String, Object> delivered(String userId, String deliveryId) {
        Instant now = clock.instant();
        return deliveries.inTransaction(tx -> {
            Courier courier = courierOf(tx, userId);
            Delivery delivery = deliveryOf(tx, deliveryId);
            if (delivery.complete(courier.id(), now)) {
                courier.free();
                tx.saveDelivery(delivery);
                tx.saveCourier(courier);
                emit(tx, delivery, () -> DeliveryEvents.completed(delivery, courier.id(), now));
            }
            return Views.delivery(delivery);
        });
    }

    /**
     * DLV-2: координата курьера. В кэш и подписчикам заказа — сразу (PERF-3),
     * в историю — пачкой. Позиция принимается только на смене.
     */
    public Map<String, Object> updateLocation(String userId, Object body) {
        Map<?, ?> map = body instanceof Map<?, ?> value ? value : Map.of();
        GeoPoint point = GeoPoint.of(number(map.get("latitude")), number(map.get("longitude")));
        LocationContext context = deliveries.locationContext(userId);
        if (context == null) {
            throw notLinked();
        }
        if (context.status() == CourierStatus.OFFLINE) {
            throw new ForbiddenException("позиция принимается только на смене");
        }
        Instant now = clock.instant();
        Position position = new Position(point.latitude(), point.longitude(), now,
                measureOrNull(map.get("accuracy")), measureOrNull(map.get("speed")));
        positions.save(context.courierId(), position);
        history.add(context.courierId(), position);
        if (context.orderId() != null) {
            positions.publish(context.orderId(), Maps.of(
                    "orderId", context.orderId(),
                    "courierId", context.courierId(),
                    "latitude", point.latitude(),
                    "longitude", point.longitude(),
                    "at", JsDates.format(now)));
        }
        return Maps.of("accepted", true);
    }

    private Courier courierOf(DeliveryTransaction tx, String userId) {
        Courier courier = tx.courierByUser(userId);
        if (courier == null) {
            throw notLinked();
        }
        return courier;
    }

    private Delivery deliveryOf(DeliveryTransaction tx, String deliveryId) {
        Delivery delivery = Ids.isUuid(deliveryId) ? tx.deliveryById(deliveryId) : null;
        if (delivery == null) {
            throw new NotFoundException("доставка не найдена", Maps.of("deliveryId", deliveryId));
        }
        return delivery;
    }

    private Offer offerOf(DeliveryTransaction tx, String offerId) {
        Offer offer = Ids.isUuid(offerId) ? tx.offerById(offerId) : null;
        if (offer == null) {
            throw new NotFoundException("предложение не найдено", Maps.of("offerId", offerId));
        }
        return offer;
    }

    /** Событие — со сквозным идентификатором запроса, начавшего подбор (OBS-2). */
    private void emit(DeliveryTransaction tx, Delivery delivery, Supplier<DomainEvent> make) {
        DomainEvent event = delivery.correlationId() != null
                ? CorrelationContext.call(delivery.correlationId(), make)
                : make.get();
        tx.enqueue(List.of(event), delivery.orderId());
    }

    /** Число из тела запроса, как {@code Number(...)} в JS: строка с числом тоже годится. */
    private static Object number(Object value) {
        if (value instanceof Number) {
            return value;
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException notNumber) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    /**
     * Точность и скорость от устройства. Вне диапазона колонки истории
     * (NUMERIC(6,2), меньше 10 000) — «не измерено»: иначе одна такая точка
     * срывала бы запись всей пачки координат.
     */
    private static Double measureOrNull(Object value) {
        if (value == null) {
            return null;
        }
        Object parsed = number(value);
        double number = ((Number) parsed).doubleValue();
        return Double.isFinite(number) && number >= 0 && number < 10_000 ? number : null;
    }
}
