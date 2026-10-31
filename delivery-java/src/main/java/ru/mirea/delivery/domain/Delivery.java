package ru.mirea.delivery.domain;

import ru.mirea.delivery.common.Maps;
import ru.mirea.delivery.error.ConflictException;
import ru.mirea.delivery.error.DomainRuleException;

import java.time.Instant;

/**
 * Доставка заказа.
 *
 * <pre>
 * PENDING ──принял──▶ ASSIGNED ──забрал──▶ PICKED_UP ──вручил──▶ DELIVERED
 *    └────────────── отмена заказа (до вручения) ──────────▶ CANCELLED
 * </pre>
 *
 * Пока доставка в PENDING, идёт подбор: курьерам по очереди уходят
 * предложения ({@link Offer}). Статус заказа доставка не меняет: о каждом
 * факте она сообщает событием, а переход выполняет {@code order}.
 */
public final class Delivery {
    private final String id;
    private final String orderId;
    private final String restaurantId;
    private final String customerId;
    private final Integer orderNumber;
    private final GeoPoint pickup;
    private final GeoPoint dropoff;
    private final String dropoffAddress;
    private final String contactPhone;
    private final Instant readyAt;
    private final String correlationId;
    private final Instant createdAt;
    private final int version;
    private DeliveryStatus status;
    private String courierId;
    private String requestKey;
    private Instant acceptedAt;
    private Instant pickedUpAt;
    private Instant deliveredAt;
    private Instant cancelledAt;
    private Instant notFoundAt;
    /**
     * Начало текущего раунда поиска. Отказавшиеся и промолчавшие в этом
     * раунде курьеры заказ повторно не получают; новый запрос от order
     * начинает новый раунд — предлагать снова можно всем.
     */
    private Instant searchStartedAt;

    private Delivery(DeliveryRecord r) {
        id = r.id();
        orderId = r.orderId();
        restaurantId = r.restaurantId();
        customerId = r.customerId();
        orderNumber = r.orderNumber();
        pickup = r.pickup();
        dropoff = r.dropoff();
        dropoffAddress = r.dropoffAddress();
        contactPhone = r.contactPhone();
        status = r.status();
        courierId = r.courierId();
        readyAt = r.readyAt();
        requestKey = r.requestKey();
        correlationId = r.correlationId();
        createdAt = r.createdAt();
        acceptedAt = r.acceptedAt();
        pickedUpAt = r.pickedUpAt();
        deliveredAt = r.deliveredAt();
        cancelledAt = r.cancelledAt();
        notFoundAt = r.notFoundAt();
        searchStartedAt = r.searchStartedAt() != null ? r.searchStartedAt() : r.createdAt();
        version = r.version();
    }

    public static Delivery restore(DeliveryRecord record) {
        return new Delivery(record);
    }

    /** Новая доставка ждёт курьера; раунд поиска начинается сейчас. */
    public static Delivery open(String id, String orderId, String restaurantId, String customerId,
                                Integer orderNumber, GeoPoint pickup, GeoPoint dropoff,
                                String dropoffAddress, String contactPhone, Instant readyAt,
                                String requestKey, String correlationId, Instant now) {
        return new Delivery(new DeliveryRecord(id, orderId, restaurantId, customerId, orderNumber,
                pickup, dropoff, dropoffAddress, contactPhone, DeliveryStatus.PENDING, null, readyAt,
                requestKey, correlationId, now, null, null, null, null, null, now, 1));
    }

    public boolean isActive() {
        return !status.isFinal();
    }

    /** Повторный запрос курьера с новым ключом — после «не найден», новый раунд поиска. */
    public void requestedAgain(String newRequestKey, Instant at) {
        requestKey = newRequestKey;
        notFoundAt = null;
        searchStartedAt = at;
    }

    public void notFound(Instant at) {
        notFoundAt = at;
    }

    public void accept(String byCourier, Instant at) {
        if (status != DeliveryStatus.PENDING) {
            throw new ConflictException("заказ уже принят другим курьером или отменён",
                    Maps.of("status", status.name()));
        }
        status = DeliveryStatus.ASSIGNED;
        courierId = byCourier;
        acceptedAt = at;
    }

    /** DLV-5. Повтор отметки тем же курьером ничего не меняет — {@code false}. */
    public boolean pickUp(String byCourier, Instant at) {
        requireCourier(byCourier);
        if (status == DeliveryStatus.PICKED_UP) {
            return false;
        }
        if (status != DeliveryStatus.ASSIGNED) {
            throw new DomainRuleException("забрать можно только принятый заказ",
                    Maps.of("status", status.name()));
        }
        status = DeliveryStatus.PICKED_UP;
        pickedUpAt = at;
        return true;
    }

    /** «Доставил» — только после «забрал»: иначе заказ вручается, не покинув ресторан. */
    public boolean complete(String byCourier, Instant at) {
        requireCourier(byCourier);
        if (status == DeliveryStatus.DELIVERED) {
            return false;
        }
        if (status != DeliveryStatus.PICKED_UP) {
            throw new DomainRuleException("сначала отметьте, что забрали заказ",
                    Maps.of("status", status.name()));
        }
        status = DeliveryStatus.DELIVERED;
        deliveredAt = at;
        return true;
    }

    /** Отмена заказа. Вручённый заказ не отменяется — его уже не вернуть. */
    public boolean cancel(Instant at) {
        if (!isActive()) {
            return false;
        }
        status = DeliveryStatus.CANCELLED;
        cancelledAt = at;
        return true;
    }

    private void requireCourier(String byCourier) {
        if (courierId == null || !courierId.equals(byCourier)) {
            throw new ConflictException("доставка назначена другому курьеру");
        }
    }

    public DeliveryRecord snapshot() {
        return new DeliveryRecord(id, orderId, restaurantId, customerId, orderNumber, pickup, dropoff,
                dropoffAddress, contactPhone, status, courierId, readyAt, requestKey, correlationId,
                createdAt, acceptedAt, pickedUpAt, deliveredAt, cancelledAt, notFoundAt, searchStartedAt,
                version);
    }

    public String id() {
        return id;
    }

    public String orderId() {
        return orderId;
    }

    public String customerId() {
        return customerId;
    }

    public GeoPoint pickup() {
        return pickup;
    }

    public GeoPoint dropoff() {
        return dropoff;
    }

    public DeliveryStatus status() {
        return status;
    }

    public String courierId() {
        return courierId;
    }

    public String requestKey() {
        return requestKey;
    }

    public String correlationId() {
        return correlationId;
    }

    public Instant notFoundAt() {
        return notFoundAt;
    }

    public Instant searchStartedAt() {
        return searchStartedAt;
    }
}
