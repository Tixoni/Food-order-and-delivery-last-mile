package ru.mirea.delivery.infra.history;

import ru.mirea.delivery.common.JsDates;
import ru.mirea.delivery.common.Log;
import ru.mirea.delivery.infra.persistence.Jdbc;
import ru.mirea.delivery.port.LocationHistory;
import ru.mirea.delivery.port.Position;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Пакетная запись истории координат.
 *
 * Координаты копятся в памяти и раз в несколько секунд уходят в базу
 * одним запросом. Тем же проходом обновляется последняя позиция курьера
 * в {@code couriers} — по ней идёт подбор (DLV-3). При падении процесса
 * теряются координаты последних секунд — для истории это допустимо.
 */
public final class BatchedLocationHistory implements LocationHistory {
    private static final Log LOG = Log.of("location-history");

    private record Entry(String courierId, Position position) {
    }

    private final DataSource dataSource;
    private final int maxBuffer;
    private final Deque<Entry> buffer = new ArrayDeque<>();
    private final ReentrantLock flushing = new ReentrantLock();

    public BatchedLocationHistory(DataSource dataSource, int maxBuffer) {
        this.dataSource = dataSource;
        this.maxBuffer = maxBuffer;
    }

    @Override
    public synchronized void add(String courierId, Position position) {
        if (buffer.size() >= maxBuffer) {
            // База недоступна долго: старые точки уступают место новым.
            buffer.pollFirst();
        }
        buffer.addLast(new Entry(courierId, position));
    }

    private synchronized List<Entry> drain() {
        List<Entry> batch = new ArrayList<>(buffer);
        buffer.clear();
        return batch;
    }

    private synchronized void restore(List<Entry> batch) {
        List<Entry> merged = new ArrayList<>(batch);
        merged.addAll(buffer);
        buffer.clear();
        merged.subList(Math.max(0, merged.size() - maxBuffer), merged.size()).forEach(buffer::addLast);
    }

    /** Записать накопленное. Возвращает число записанных точек. */
    public int flush() {
        if (!flushing.tryLock()) {
            return 0;
        }
        try {
            List<Entry> batch = drain();
            if (batch.isEmpty()) {
                return 0;
            }
            try {
                write(batch);
                return batch.size();
            } catch (RuntimeException | SQLException error) {
                restore(batch);
                LOG.warn("история координат не записана, повтор на следующем шаге",
                        "points", batch.size(), "error", error.getMessage());
                return 0;
            }
        } finally {
            flushing.unlock();
        }
    }

    private void write(List<Entry> batch) throws SQLException {
        Map<String, Position> latest = new LinkedHashMap<>();
        batch.forEach(entry -> latest.put(entry.courierId(), entry.position()));
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Jdbc.update(conn, """
                        INSERT INTO courier_locations (courier_id, location, speed, accuracy, recorded_at)
                        SELECT u.courier_id,
                               ST_SetSRID(ST_MakePoint(u.lon, u.lat), 4326)::geography,
                               u.speed, u.accuracy, u.recorded_at
                          FROM unnest(?::uuid[], ?::float8[], ?::float8[], ?::numeric[], ?::numeric[],
                                      ?::timestamptz[])
                            AS u(courier_id, lat, lon, speed, accuracy, recorded_at)""",
                        Jdbc.array(conn, "uuid", batch.stream().map(e -> UUID.fromString(e.courierId())).toArray()),
                        Jdbc.array(conn, "float8", batch.stream().map(e -> e.position().latitude()).toArray()),
                        Jdbc.array(conn, "float8", batch.stream().map(e -> e.position().longitude()).toArray()),
                        Jdbc.array(conn, "float8", batch.stream().map(e -> e.position().speed()).toArray()),
                        Jdbc.array(conn, "float8", batch.stream().map(e -> e.position().accuracy()).toArray()),
                        Jdbc.array(conn, "text", batch.stream().map(e -> JsDates.format(e.position().at())).toArray()));
                Jdbc.update(conn, """
                        UPDATE couriers c
                           SET last_location = ST_SetSRID(ST_MakePoint(u.lon, u.lat), 4326)::geography,
                               last_location_at = u.recorded_at
                          FROM unnest(?::uuid[], ?::float8[], ?::float8[], ?::timestamptz[])
                            AS u(courier_id, lat, lon, recorded_at)
                         WHERE c.id = u.courier_id
                           AND (c.last_location_at IS NULL OR c.last_location_at < u.recorded_at)""",
                        Jdbc.array(conn, "uuid", latest.keySet().stream().map(UUID::fromString).toArray()),
                        Jdbc.array(conn, "float8", latest.values().stream().map(Position::latitude).toArray()),
                        Jdbc.array(conn, "float8", latest.values().stream().map(Position::longitude).toArray()),
                        Jdbc.array(conn, "text", latest.values().stream().map(p -> JsDates.format(p.at())).toArray()));
                conn.commit();
            } catch (RuntimeException error) {
                conn.rollback();
                throw error;
            }
        }
    }
}
