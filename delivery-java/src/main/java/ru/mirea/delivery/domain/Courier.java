package ru.mirea.delivery.domain;

import ru.mirea.delivery.common.Maps;
import ru.mirea.delivery.error.DomainRuleException;

import java.time.Instant;

/**
 * Курьер и его статус.
 *
 * <pre>
 *   offline ──смена──▶ available ──предложение──▶ assigned ──принял──▶ busy
 *      ▲                 ▲  ▲                         │                  │
 *      └──конец смены────┘  └──отказ / молчание───────┘                  │
 *                           └──────────────доставил или заказ отменён────┘
 * </pre>
 *
 * Статус меняется только методами-переходами: каждый проверяет, допустим
 * ли переход, и бросает {@link DomainRuleException}, если нет. Снаружи
 * поле статуса не изменить.
 *
 * Класс абстрактный: вид транспорта задаёт подкласс ({@link FootCourier},
 * {@link BikeCourier}, {@link CarCourier}) — от него зависят скорость,
 * вместимость и, значит, время в пути. Подбор курьера и прогноз доставки
 * спрашивают время у самого курьера, не ветвясь по типу транспорта.
 */
public abstract class Courier {
    private final String id;
    private final String userId;
    private final String fullName;
    private final String phone;
    private final boolean active;
    private final GeoPoint lastLocation;
    private final Instant lastLocationAt;
    private final VehicleProfile profile;
    private CourierStatus status;
    private String currentDeliveryId;

    protected Courier(CourierRecord record, VehicleProfile profile) {
        this.id = record.id();
        this.userId = record.userId();
        this.fullName = record.fullName();
        this.phone = record.phone();
        this.active = record.active();
        this.lastLocation = record.lastLocation();
        this.lastLocationAt = record.lastLocationAt();
        this.status = record.status();
        this.currentDeliveryId = record.currentDeliveryId();
        this.profile = profile != null ? profile : defaultProfile();
    }

    /** Курьер нужного вида по сохранённому состоянию. */
    public static Courier restore(CourierRecord record) {
        return create(record, null);
    }

    /** Курьер нужного вида с особым профилем транспорта (планировщик берёт его из условия задачи). */
    public static Courier create(CourierRecord record, VehicleProfile profile) {
        return switch (record.vehicleType()) {
            case FOOT -> new FootCourier(record, profile);
            case BIKE -> new BikeCourier(record, profile);
            case CAR -> new CarCourier(record, profile);
        };
    }

    /** ADM-2: новый курьер заводится администратором и начинает вне смены. */
    public static Courier register(String id, String fullName, VehicleType type, String userId, String phone) {
        return restore(new CourierRecord(id, userId, fullName, phone, type, CourierStatus.OFFLINE,
                null, true, null, null));
    }

    public abstract VehicleType vehicleType();

    protected abstract VehicleProfile defaultProfile();

    public double speedKmh() {
        return profile.speedKmh();
    }

    public double capacity() {
        return profile.capacity();
    }

    /** Время в пути по дорогам, секунды. */
    public double travelSeconds(double roadMeters) {
        return roadMeters / 1000 / speedKmh() * 3600;
    }

    /** Время в пути по дорогам, минуты (планировщик считает в минутах). */
    public double travelMinutes(double roadMeters) {
        return roadMeters / 1000 / speedKmh() * 60;
    }

    /** Прогноз в минутах (DLV-6): по длине маршрута, без маршрутизатора — по прямой с поправкой. */
    public int etaMinutes(GeoPoint from, GeoPoint to, Double roadMeters) {
        double meters = roadMeters != null ? roadMeters : from.distanceTo(to) * GeoPoint.DETOUR;
        return (int) Math.max(1, Math.ceil(travelSeconds(meters) / 60));
    }

    /** DLV-1. Повторное «на смену» ничего не меняет — {@code false}. */
    public boolean startShift() {
        if (!active) {
            throw new DomainRuleException("курьер отключён администратором");
        }
        if (userId == null) {
            throw new DomainRuleException("курьер не привязан к учётной записи — смена невозможна");
        }
        if (status != CourierStatus.OFFLINE) {
            return false;
        }
        status = CourierStatus.AVAILABLE;
        return true;
    }

    /** Уйти со смены можно только свободным: с заказом на руках — сначала доставка. */
    public boolean endShift() {
        if (status == CourierStatus.OFFLINE) {
            return false;
        }
        if (status != CourierStatus.AVAILABLE) {
            throw new DomainRuleException("сначала ответьте на предложение или завершите доставку",
                    Maps.of("status", status.wire()));
        }
        status = CourierStatus.OFFLINE;
        return true;
    }

    public void offer() {
        require(CourierStatus.AVAILABLE, "предложить заказ можно только свободному курьеру");
        status = CourierStatus.ASSIGNED;
    }

    public void accept(String deliveryId) {
        require(CourierStatus.ASSIGNED, "принять можно только предложенный заказ");
        status = CourierStatus.BUSY;
        currentDeliveryId = deliveryId;
    }

    /** Отказ, молчание или отмена заказа до ответа — снова свободен. */
    public void declineOffer() {
        if (status == CourierStatus.ASSIGNED) {
            status = CourierStatus.AVAILABLE;
        }
    }

    /** Заказ вручён или отменён — курьер свободен (если не ушёл со смены). */
    public void free() {
        if (status == CourierStatus.ASSIGNED || status == CourierStatus.BUSY) {
            status = CourierStatus.AVAILABLE;
        }
        currentDeliveryId = null;
    }

    private void require(CourierStatus expected, String message) {
        if (status != expected) {
            throw new DomainRuleException(message, Maps.of("status", status.wire()));
        }
    }

    public CourierRecord snapshot() {
        return new CourierRecord(id, userId, fullName, phone, vehicleType(), status, currentDeliveryId,
                active, lastLocation, lastLocationAt);
    }

    public String id() {
        return id;
    }

    public String userId() {
        return userId;
    }

    public String fullName() {
        return fullName;
    }

    public String phone() {
        return phone;
    }

    public boolean active() {
        return active;
    }

    public CourierStatus status() {
        return status;
    }

    public String currentDeliveryId() {
        return currentDeliveryId;
    }

    public GeoPoint lastLocation() {
        return lastLocation;
    }

    public Instant lastLocationAt() {
        return lastLocationAt;
    }
}
