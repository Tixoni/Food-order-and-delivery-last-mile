package ru.mirea.delivery.infra.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import ru.mirea.delivery.common.Json;
import ru.mirea.delivery.common.Log;
import ru.mirea.delivery.infra.persistence.Jdbc;
import ru.mirea.delivery.port.MessageBroker;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Отправка событий в брокер — вторая половина Transactional Outbox (REL-3).
 *
 * Запрос завершается, как только событие записано в {@code outbox_messages}
 * той же транзакцией, что и изменение данных. Этот проход забирает
 * неотправленные строки и публикует их. Брокер недоступен — события
 * копятся в таблице и уходят после восстановления. Гарантия — «хотя бы
 * один раз»; от дублей защищается потребитель по {@code messageId}.
 */
public final class OutboxRelay {
    private static final Log LOG = Log.of("outbox");
    private static final int PARTITIONS = 3;
    private static final long TOPIC_RETRY_NANOS = 5_000_000_000L;

    private record Row(long id, String aggregateId, String eventType, String payload, String headers) {
    }

    private final DataSource dataSource;
    private final MessageBroker broker;
    private final String topic;
    private final int batchSize;
    private boolean topicReady;
    private long nextTopicAttempt;

    public OutboxRelay(DataSource dataSource, MessageBroker broker, String topic, int batchSize) {
        this.dataSource = dataSource;
        this.broker = broker;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    /** Один проход; возвращает число отправленных событий. */
    public int tick() {
        ensureTopic();
        if (!topicReady) {
            return 0;
        }
        try {
            return publishBatch();
        } catch (SQLException | RuntimeException error) {
            LOG.warn("не удалось отправить события в брокер", "topic", topic, "error", error.getMessage());
            return 0;
        }
    }

    /** Топик создаётся явно; брокер недоступен при запуске — повтор через 5 с. */
    private void ensureTopic() {
        if (topicReady || System.nanoTime() < nextTopicAttempt) {
            return;
        }
        nextTopicAttempt = System.nanoTime() + TOPIC_RETRY_NANOS;
        try {
            broker.ensureTopic(topic, PARTITIONS);
            topicReady = true;
        } catch (RuntimeException error) {
            LOG.warn("топик событий не подготовлен", "topic", topic, "error", error.getMessage());
        }
    }

    private int publishBatch() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int published = publishBatch(conn);
                conn.commit();
                return published;
            } catch (RuntimeException error) {
                conn.rollback();
                throw error;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private int publishBatch(Connection conn) {
        List<Row> rows = Jdbc.query(conn, """
                SELECT id, aggregate_id, event_type, payload::text AS payload, headers::text AS headers
                  FROM outbox_messages
                 WHERE published_at IS NULL
                 ORDER BY id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED""",
                row -> new Row(row.getLong("id"), Jdbc.text(row, "aggregate_id"), row.getString("event_type"),
                        row.getString("payload"), row.getString("headers")),
                batchSize);
        if (rows.isEmpty()) {
            return 0;
        }
        Object ids = Jdbc.array(conn, "bigint", rows.stream().map(Row::id).toArray());
        try {
            List<MessageBroker.Message> messages = rows.stream().map(OutboxRelay::toMessage).toList();
            broker.send(topic, messages);
        } catch (RuntimeException error) {
            // Число попыток и последняя ошибка — в таблице: видно, что застряло и почему.
            String reason = String.valueOf(error.getMessage());
            Jdbc.update(conn, """
                    UPDATE outbox_messages SET attempts = attempts + 1, last_error = ?
                     WHERE id = ANY(?::bigint[])""", reason.substring(0, Math.min(1000, reason.length())), ids);
            broker.reset();
            LOG.warn("не удалось отправить события в брокер", "topic", topic, "error", reason);
            return 0;
        }
        Jdbc.update(conn, "UPDATE outbox_messages SET published_at = now() WHERE id = ANY(?::bigint[])", ids);
        LOG.debug("события отправлены", "topic", topic, "count", rows.size(),
                "types", new TreeSet<>(rows.stream().map(Row::eventType).toList()));
        return rows.size();
    }

    /** Ключ партиции задаёт порядок: события одного заказа — в одну партицию. */
    private static MessageBroker.Message toMessage(Row row) {
        try {
            JsonNode payload = Json.mapper().readTree(row.payload());
            JsonNode headerNode = Json.mapper().readTree(row.headers() == null ? "{}" : row.headers());
            Map<String, String> headers = new LinkedHashMap<>();
            headerNode.fields().forEachRemaining(field -> headers.put(field.getKey(),
                    field.getValue().isTextual() ? field.getValue().asText() : field.getValue().toString()));
            String key = headers.getOrDefault("partitionKey", row.aggregateId());
            return new MessageBroker.Message(key, Json.write(payload), headers);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("событие в outbox не разобрано: " + row.id(), error);
        }
    }
}
