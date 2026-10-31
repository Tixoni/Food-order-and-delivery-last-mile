package ru.mirea.delivery.infra.persistence;

import ru.mirea.delivery.common.Json;
import ru.mirea.delivery.domain.Courier;
import ru.mirea.delivery.domain.CourierRecord;
import ru.mirea.delivery.domain.Delivery;
import ru.mirea.delivery.domain.DeliveryRecord;
import ru.mirea.delivery.domain.GeoPoint;
import ru.mirea.delivery.domain.Offer;
import ru.mirea.delivery.domain.OfferRecord;
import ru.mirea.delivery.port.Candidate;
import ru.mirea.delivery.port.DeliveryTransaction;
import ru.mirea.delivery.port.DomainEvent;
import ru.mirea.delivery.port.DuplicateDeliveryException;
import ru.mirea.delivery.port.RoadRoute;

import java.sql.Connection;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ru.mirea.delivery.infra.persistence.Rows.COURIER_COLUMNS;
import static ru.mirea.delivery.infra.persistence.Rows.DELIVERY_COLUMNS;
import static ru.mirea.delivery.infra.persistence.Rows.OFFER_COLUMNS;
import static ru.mirea.delivery.infra.persistence.Rows.POINT;

/**
 * Операции в одной транзакции базы. Подбор (DLV-3) — запрос по
 * пространственному индексу: {@code ST_DWithin} отсекает дальних,
 * {@code <->} сортирует от ближнего (KNN по GiST), {@code FOR UPDATE …
 * SKIP LOCKED} пропускает тех, кого прямо сейчас предлагает другой
 * экземпляр сервиса.
 */
final class SqlDeliveryTransaction implements DeliveryTransaction {
    private final Connection conn;

    SqlDeliveryTransaction(Connection conn) {
        this.conn = conn;
    }

    @Override
    public Delivery deliveryById(String id) {
        return Jdbc.queryOne(conn, "SELECT " + DELIVERY_COLUMNS + " FROM deliveries d WHERE d.id = ? FOR UPDATE",
                Rows::delivery, id);
    }

    @Override
    public Delivery activeDeliveryByOrder(String orderId) {
        return Jdbc.queryOne(conn, "SELECT " + DELIVERY_COLUMNS + """
                 FROM deliveries d
                WHERE d.order_id = ? AND d.status NOT IN ('CANCELLED', 'DELIVERED')
                FOR UPDATE""", Rows::delivery, orderId);
    }

    @Override
    public void insertDelivery(Delivery delivery) {
        DeliveryRecord d = delivery.snapshot();
        try {
            Jdbc.update(conn, """
                    INSERT INTO deliveries
                      (id, order_id, restaurant_id, customer_id, pickup_location, dropoff_location,
                       status, ready_at, request_key, correlation_id, created_at,
                       order_number, dropoff_address, contact_phone, search_started_at)
                    VALUES (?, ?, ?, ?, %s, %s, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".formatted(POINT, POINT),
                    d.id(), d.orderId(), d.restaurantId(), d.customerId(),
                    d.pickup().longitude(), d.pickup().latitude(),
                    d.dropoff().longitude(), d.dropoff().latitude(),
                    d.status().name(), d.readyAt(), d.requestKey(), d.correlationId(), d.createdAt(),
                    d.orderNumber(), d.dropoffAddress(), d.contactPhone(), d.searchStartedAt());
        } catch (Jdbc.DataAccessException error) {
            // ux_deliveries_order_active: одновременный запрос успел первым
            if (Jdbc.UNIQUE_VIOLATION.equals(error.sqlState())) {
                throw new DuplicateDeliveryException(d.orderId(), error);
            }
            throw error;
        }
    }

    @Override
    public void saveDelivery(Delivery delivery) {
        DeliveryRecord d = delivery.snapshot();
        Jdbc.update(conn, """
                UPDATE deliveries
                   SET status = ?, courier_id = ?, request_key = ?, not_found_at = ?,
                       accepted_at = ?, assigned_at = COALESCE(assigned_at, ?), picked_up_at = ?,
                       delivered_at = ?, cancelled_at = ?, search_started_at = ?,
                       version = version + 1
                 WHERE id = ?""",
                d.status().name(), d.courierId(), d.requestKey(), d.notFoundAt(), d.acceptedAt(), d.acceptedAt(),
                d.pickedUpAt(), d.deliveredAt(), d.cancelledAt(), d.searchStartedAt(), d.id());
    }

    @Override
    public Courier courierById(String id) {
        return Jdbc.queryOne(conn, "SELECT " + COURIER_COLUMNS + " FROM couriers c WHERE c.id = ? FOR UPDATE",
                Rows::courier, id);
    }

    @Override
    public Courier courierByUser(String userId) {
        return Jdbc.queryOne(conn, "SELECT " + COURIER_COLUMNS + " FROM couriers c WHERE c.user_id = ? FOR UPDATE",
                Rows::courier, userId);
    }

    @Override
    public void insertCourier(Courier courier) {
        CourierRecord c = courier.snapshot();
        Jdbc.update(conn, """
                INSERT INTO couriers (id, user_id, full_name, phone, vehicle_type, status, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                c.id(), c.userId(), c.fullName(), c.phone(), c.vehicleType().wire(), c.status().wire(), c.active());
    }

    @Override
    public void saveCourier(Courier courier) {
        CourierRecord c = courier.snapshot();
        Jdbc.update(conn, "UPDATE couriers SET status = ?, current_delivery_id = ?, is_active = ? WHERE id = ?",
                c.status().wire(), c.currentDeliveryId(), c.active(), c.id());
    }

    @Override
    public void openShift(String courierId, Instant at) {
        Jdbc.update(conn, """
                INSERT INTO courier_shifts (courier_id, started_at) VALUES (?, ?)
                ON CONFLICT (courier_id) WHERE ended_at IS NULL DO NOTHING""", courierId, at);
    }

    @Override
    public void closeShift(String courierId, Instant at) {
        Jdbc.update(conn, """
                UPDATE courier_shifts SET ended_at = GREATEST(?, started_at + interval '1 millisecond')
                 WHERE courier_id = ? AND ended_at IS NULL""", at, courierId);
    }

    /**
     * Два шага, как в TS-версии: сначала десять ближайших (MATERIALIZED не
     * даёт планировщику склеить шаги и блокировать строки на KNN-сканировании),
     * затем блокировка строки курьера по первичному ключу с перепроверкой
     * статуса. Порядок из маршрутизатора применяется к тем же десяти; кого
     * в нём нет — после, по прямой.
     */
    @Override
    public Candidate nearestCandidate(Delivery delivery, int radiusMeters, int freshSeconds, List<String> preferred) {
        GeoPoint pickup = delivery.pickup();
        Object order = preferred == null || preferred.isEmpty() ? null
                : Jdbc.array(conn, "uuid", preferred.stream().map(UUID::fromString).toArray());
        return Jdbc.queryOne(conn, """
                WITH nearest AS MATERIALIZED (
                  SELECT c.id, c.last_location <-> %1$s AS distance
                    FROM couriers c
                   WHERE c.status = 'available'
                     AND c.is_active
                     AND c.user_id IS NOT NULL
                     AND c.last_location_at > now() - make_interval(secs => ?)
                     AND ST_DWithin(c.last_location, %1$s, ?)
                     AND NOT EXISTS (
                           SELECT 1 FROM delivery_assignments a
                            WHERE a.delivery_id = ? AND a.courier_id = c.id
                              AND a.offered_at >= ?)
                   ORDER BY c.last_location <-> %1$s
                   LIMIT 10)
                SELECT %2$s, n.distance
                  FROM nearest n
                  JOIN couriers c ON c.id = n.id
                 WHERE c.status = 'available'
                 ORDER BY COALESCE(array_position(?::uuid[], n.id), 2147483647), n.distance
                 LIMIT 1
                 FOR UPDATE OF c SKIP LOCKED""".formatted(POINT, COURIER_COLUMNS),
                row -> new Candidate(Rows.courier(row), row.getDouble("distance")),
                pickup.longitude(), pickup.latitude(),
                (double) freshSeconds,
                pickup.longitude(), pickup.latitude(), (double) radiusMeters,
                delivery.id(), delivery.searchStartedAt(),
                pickup.longitude(), pickup.latitude(),
                order);
    }

    @Override
    public Offer openOfferOf(String deliveryId) {
        return Jdbc.queryOne(conn, "SELECT " + OFFER_COLUMNS + """
                 FROM delivery_assignments a
                WHERE a.delivery_id = ? AND a.response IS NULL FOR UPDATE""", Rows::offer, deliveryId);
    }

    @Override
    public Offer offerById(String id) {
        return Jdbc.queryOne(conn, "SELECT " + OFFER_COLUMNS + " FROM delivery_assignments a WHERE a.id = ? FOR UPDATE",
                Rows::offer, id);
    }

    @Override
    public int offersCount(String deliveryId) {
        return Jdbc.queryOne(conn, "SELECT count(*) AS count FROM delivery_assignments WHERE delivery_id = ?",
                row -> row.getInt("count"), deliveryId);
    }

    @Override
    public void insertOffer(Offer offer) {
        OfferRecord o = offer.snapshot();
        Jdbc.update(conn, """
                INSERT INTO delivery_assignments (id, delivery_id, courier_id, sequence, offered_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)""",
                o.id(), o.deliveryId(), o.courierId(), o.sequence(), o.offeredAt(), o.expiresAt());
    }

    @Override
    public void saveOffer(Offer offer) {
        OfferRecord o = offer.snapshot();
        Jdbc.update(conn, "UPDATE delivery_assignments SET response = ?, responded_at = ? WHERE id = ?",
                o.response() == null ? null : o.response().name(), o.respondedAt(), o.id());
    }

    @Override
    public List<Offer> dueOffers(Instant now, int limit) {
        return Jdbc.query(conn, "SELECT " + OFFER_COLUMNS + """
                 FROM delivery_assignments a
                WHERE a.response IS NULL AND a.expires_at <= ?
                ORDER BY a.expires_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED""", Rows::offer, now, limit);
    }

    @Override
    public void enqueue(List<DomainEvent> events, String orderId) {
        for (DomainEvent event : events) {
            Map<String, Object> headers = new LinkedHashMap<>();
            // События одного заказа — в одну партицию, по порядку.
            headers.put("partitionKey", orderId);
            headers.put("correlationId", event.correlationId());
            Jdbc.update(conn, """
                    INSERT INTO outbox_messages
                      (aggregate_type, aggregate_id, event_type, event_version, payload, headers)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)""",
                    event.aggregateType(), event.aggregateId(), event.eventType(), event.eventVersion(),
                    Json.write(event.toMap()), Json.write(headers));
        }
    }

    @Override
    public RoadRoute routeOf(String deliveryId) {
        return SqlDeliveryRepository.routeOf(conn, deliveryId);
    }
}
