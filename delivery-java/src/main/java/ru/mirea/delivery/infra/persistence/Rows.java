package ru.mirea.delivery.infra.persistence;

import ru.mirea.delivery.domain.Courier;
import ru.mirea.delivery.domain.CourierRecord;
import ru.mirea.delivery.domain.CourierStatus;
import ru.mirea.delivery.domain.Delivery;
import ru.mirea.delivery.domain.DeliveryRecord;
import ru.mirea.delivery.domain.DeliveryStatus;
import ru.mirea.delivery.domain.Offer;
import ru.mirea.delivery.domain.OfferRecord;
import ru.mirea.delivery.domain.OfferResponse;
import ru.mirea.delivery.domain.VehicleType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

import static ru.mirea.delivery.infra.persistence.Jdbc.instant;
import static ru.mirea.delivery.infra.persistence.Jdbc.point;
import static ru.mirea.delivery.infra.persistence.Jdbc.text;

/** Столбцы запросов и превращение строк таблиц в объекты предметной области. */
final class Rows {
    static final String COURIER_COLUMNS = """
             c.id, c.user_id, c.full_name, c.phone, c.vehicle_type, c.status,
             c.current_delivery_id, c.is_active, c.last_location_at,
             ST_Y(c.last_location::geometry) AS lat, ST_X(c.last_location::geometry) AS lon""";

    static final String DELIVERY_COLUMNS = """
             d.id, d.order_id, d.restaurant_id, d.customer_id, d.order_number, d.dropoff_address,
             d.contact_phone, d.status, d.courier_id, d.ready_at,
             d.request_key, d.correlation_id, d.created_at, d.accepted_at, d.picked_up_at,
             d.delivered_at, d.cancelled_at, d.not_found_at, d.search_started_at, d.version,
             ST_Y(d.pickup_location::geometry) AS pickup_lat, ST_X(d.pickup_location::geometry) AS pickup_lon,
             ST_Y(d.dropoff_location::geometry) AS dropoff_lat, ST_X(d.dropoff_location::geometry) AS dropoff_lon""";

    static final String OFFER_COLUMNS = """
             a.id, a.delivery_id, a.courier_id, a.sequence, a.offered_at, a.expires_at,
             a.responded_at, a.response""";

    /** Точка в запросе: долгота, широта — два параметра. */
    static final String POINT = "ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography";

    private Rows() {
    }

    static Courier courier(ResultSet row) throws SQLException {
        return Courier.restore(new CourierRecord(
                text(row, "id"),
                text(row, "user_id"),
                row.getString("full_name"),
                row.getString("phone"),
                VehicleType.fromWire(row.getString("vehicle_type")),
                CourierStatus.fromWire(row.getString("status")),
                text(row, "current_delivery_id"),
                row.getBoolean("is_active"),
                point(row, "lat", "lon"),
                instant(row, "last_location_at")));
    }

    static Delivery delivery(ResultSet row) throws SQLException {
        Object orderNumber = row.getObject("order_number");
        Instant created = instant(row, "created_at");
        Instant searchStarted = instant(row, "search_started_at");
        return Delivery.restore(new DeliveryRecord(
                text(row, "id"),
                text(row, "order_id"),
                text(row, "restaurant_id"),
                text(row, "customer_id"),
                orderNumber == null ? null : ((Number) orderNumber).intValue(),
                point(row, "pickup_lat", "pickup_lon"),
                point(row, "dropoff_lat", "dropoff_lon"),
                row.getString("dropoff_address"),
                row.getString("contact_phone"),
                DeliveryStatus.valueOf(row.getString("status")),
                text(row, "courier_id"),
                instant(row, "ready_at"),
                row.getString("request_key"),
                row.getString("correlation_id"),
                created,
                instant(row, "accepted_at"),
                instant(row, "picked_up_at"),
                instant(row, "delivered_at"),
                instant(row, "cancelled_at"),
                instant(row, "not_found_at"),
                searchStarted != null ? searchStarted : created,
                row.getInt("version")));
    }

    static Offer offer(ResultSet row) throws SQLException {
        String response = row.getString("response");
        return Offer.restore(new OfferRecord(
                text(row, "id"),
                text(row, "delivery_id"),
                text(row, "courier_id"),
                row.getInt("sequence"),
                instant(row, "offered_at"),
                instant(row, "expires_at"),
                instant(row, "responded_at"),
                response == null ? null : OfferResponse.valueOf(response)));
    }

}
