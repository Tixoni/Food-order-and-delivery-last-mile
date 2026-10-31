package ru.mirea.delivery.infra.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import ru.mirea.delivery.common.Json;
import ru.mirea.delivery.domain.Courier;
import ru.mirea.delivery.domain.CourierRecord;
import ru.mirea.delivery.domain.CourierStatus;
import ru.mirea.delivery.domain.Delivery;
import ru.mirea.delivery.domain.GeoPoint;
import ru.mirea.delivery.domain.Offer;
import ru.mirea.delivery.domain.VehicleType;
import ru.mirea.delivery.port.CandidateInfo;
import ru.mirea.delivery.port.Candidates;
import ru.mirea.delivery.port.CourierDashboard;
import ru.mirea.delivery.port.DeliveryRepository;
import ru.mirea.delivery.port.LocationContext;
import ru.mirea.delivery.port.RoadRoute;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ru.mirea.delivery.infra.persistence.Rows.COURIER_COLUMNS;
import static ru.mirea.delivery.infra.persistence.Rows.DELIVERY_COLUMNS;
import static ru.mirea.delivery.infra.persistence.Rows.OFFER_COLUMNS;

/**
 * Хранилище доставки на PostgreSQL + PostGIS — те же таблицы и запросы,
 * что у TS-версии ({@code sql-delivery.repository.ts}).
 */
public final class SqlDeliveryRepository implements DeliveryRepository {
    private final DataSource dataSource;

    public SqlDeliveryRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @FunctionalInterface
    private interface ConnectionWork<T> {
        T run(Connection conn);
    }

    private <T> T withConnection(ConnectionWork<T> work) {
        try (Connection conn = dataSource.getConnection()) {
            return work.run(conn);
        } catch (SQLException error) {
            throw new Jdbc.DataAccessException(error);
        }
    }

    @Override
    public <T> T inTransaction(TransactionWork<T> work) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                T result = work.run(new SqlDeliveryTransaction(conn));
                conn.commit();
                return result;
            } catch (RuntimeException error) {
                conn.rollback();
                throw error;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new Jdbc.DataAccessException(error);
        }
    }

    @Override
    public CourierDashboard dashboard(String userId) {
        return withConnection(conn -> {
            Courier courier = Jdbc.queryOne(conn,
                    "SELECT " + COURIER_COLUMNS + " FROM couriers c WHERE c.user_id = ?", Rows::courier, userId);
            if (courier == null) {
                return null;
            }
            Offer offer = Jdbc.queryOne(conn, "SELECT " + OFFER_COLUMNS + """
                     FROM delivery_assignments a
                    WHERE a.courier_id = ? AND a.response IS NULL""", Rows::offer, courier.id());
            Delivery offerDelivery = offer != null ? deliveryById(conn, offer.deliveryId()) : null;
            Delivery current = courier.currentDeliveryId() != null
                    ? deliveryById(conn, courier.currentDeliveryId()) : null;
            return offer != null && offerDelivery != null
                    ? new CourierDashboard(courier, offer, offerDelivery, current)
                    : new CourierDashboard(courier, null, null, current);
        });
    }

    private static Delivery deliveryById(Connection conn, String id) {
        return Jdbc.queryOne(conn, "SELECT " + DELIVERY_COLUMNS + " FROM deliveries d WHERE d.id = ?",
                Rows::delivery, id);
    }

    @Override
    public Delivery deliveryOfOrder(String orderId) {
        return withConnection(conn -> Jdbc.queryOne(conn, "SELECT " + DELIVERY_COLUMNS + """
                 FROM deliveries d
                WHERE d.order_id = ? ORDER BY d.created_at DESC LIMIT 1""", Rows::delivery, orderId));
    }

    @Override
    public LocationContext locationContext(String userId) {
        return withConnection(conn -> Jdbc.queryOne(conn, """
                SELECT c.id, c.status, d.order_id
                  FROM couriers c
                  LEFT JOIN deliveries d ON d.id = c.current_delivery_id
                 WHERE c.user_id = ?""",
                row -> new LocationContext(Jdbc.text(row, "id"), CourierStatus.fromWire(row.getString("status")),
                        Jdbc.text(row, "order_id")),
                userId));
    }

    @Override
    public List<Courier> listCouriers() {
        return withConnection(conn -> Jdbc.query(conn,
                "SELECT " + COURIER_COLUMNS + " FROM couriers c ORDER BY c.full_name", Rows::courier));
    }

    private record CandidateRow(GeoPoint pickup, String id, String vehicle, double distance, GeoPoint position) {
    }

    @Override
    public Candidates candidates(String deliveryId, int radiusMeters, int freshSeconds, int limit) {
        List<CandidateRow> rows = withConnection(conn -> Jdbc.query(conn, """
                SELECT ST_Y(d.pickup_location::geometry) AS pickup_lat,
                       ST_X(d.pickup_location::geometry) AS pickup_lon,
                       n.id, n.vehicle_type, n.distance,
                       ST_Y(n.last_location::geometry) AS lat, ST_X(n.last_location::geometry) AS lon
                  FROM deliveries d
                  LEFT JOIN LATERAL (
                    SELECT c.id, c.vehicle_type, c.last_location,
                           c.last_location <-> d.pickup_location AS distance
                      FROM couriers c
                     WHERE c.status = 'available'
                       AND c.is_active
                       AND c.user_id IS NOT NULL
                       AND c.last_location_at > now() - make_interval(secs => ?)
                       AND ST_DWithin(c.last_location, d.pickup_location, ?)
                       AND NOT EXISTS (
                             SELECT 1 FROM delivery_assignments a
                              WHERE a.delivery_id = d.id AND a.courier_id = c.id
                                AND a.offered_at >= d.search_started_at)
                     ORDER BY c.last_location <-> d.pickup_location
                     LIMIT ?) n ON true
                 WHERE d.id = ? AND d.status = 'PENDING'
                 ORDER BY n.distance""",
                row -> new CandidateRow(
                        Jdbc.point(row, "pickup_lat", "pickup_lon"),
                        Jdbc.text(row, "id"),
                        row.getString("vehicle_type"),
                        row.getDouble("distance"),
                        Jdbc.point(row, "lat", "lon")),
                (double) freshSeconds, (double) radiusMeters, limit, deliveryId));
        if (rows.isEmpty()) {
            return null;
        }
        List<CandidateInfo> found = new ArrayList<>();
        for (CandidateRow row : rows) {
            if (row.id() == null) {
                continue;
            }
            Courier courier = Courier.restore(new CourierRecord(row.id(), null, "", null,
                    VehicleType.fromWire(row.vehicle()), CourierStatus.AVAILABLE, null, true, row.position(), null));
            found.add(new CandidateInfo(courier, row.position(), row.distance()));
        }
        return new Candidates(rows.get(0).pickup(), found);
    }

    @Override
    public RoadRoute routeOf(String deliveryId) {
        return withConnection(conn -> routeOf(conn, deliveryId));
    }

    static RoadRoute routeOf(Connection conn, String deliveryId) {
        return Jdbc.queryOne(conn, """
                SELECT distance_m, ST_AsGeoJSON(path::geometry, 6) AS path
                  FROM delivery_routes WHERE delivery_id = ?""",
                row -> {
                    List<GeoPoint> path = new ArrayList<>();
                    try {
                        JsonNode line = Json.mapper().readTree(row.getString("path"));
                        for (JsonNode coordinate : line.get("coordinates")) {
                            path.add(new GeoPoint(coordinate.get(1).asDouble(), coordinate.get(0).asDouble()));
                        }
                    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                        throw new SQLException("линия маршрута не разобрана", error);
                    }
                    return new RoadRoute(row.getLong("distance_m"), path);
                },
                deliveryId);
    }

    @Override
    public void saveRoute(String deliveryId, RoadRoute route) {
        // Линии нужны хотя бы две точки; у маршрута «в ту же точку» она одна.
        List<GeoPoint> path = route.path().size() == 1
                ? List.of(route.path().get(0), route.path().get(0)) : route.path();
        if (path.size() < 2) {
            return;
        }
        List<List<Double>> coordinates = path.stream()
                .map(point -> List.of(point.longitude(), point.latitude())).toList();
        String geometry = Json.write(Map.of("type", "LineString", "coordinates", coordinates));
        withConnection(conn -> Jdbc.update(conn, """
                INSERT INTO delivery_routes (delivery_id, distance_m, path)
                VALUES (?, ?, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)::geography)
                ON CONFLICT (delivery_id) DO NOTHING""", deliveryId, route.distanceMeters(), geometry));
    }
}
