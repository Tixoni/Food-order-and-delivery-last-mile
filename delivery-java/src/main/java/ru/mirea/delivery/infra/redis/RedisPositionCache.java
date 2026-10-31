package ru.mirea.delivery.infra.redis;

import ru.mirea.delivery.common.JsDates;
import ru.mirea.delivery.common.Json;
import ru.mirea.delivery.common.Log;
import ru.mirea.delivery.config.AppConfig;
import ru.mirea.delivery.port.Position;
import ru.mirea.delivery.port.PositionCache;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.exceptions.JedisException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Актуальная позиция курьера в Redis и рассылка подписчикам заказа.
 *
 * Позиция живёт 60 секунд без обновлений: курьер, потерявший связь,
 * «устаревает» сам. Недоступность Redis доставку не останавливает:
 * координата всё равно попадёт в историю, подбор идёт по базе.
 */
public final class RedisPositionCache implements PositionCache, AutoCloseable {
    private static final Log LOG = Log.of("positions");

    private final JedisPool pool;
    private final int ttlSeconds;

    public RedisPositionCache(AppConfig.Redis config, int ttlSeconds) {
        this.pool = new JedisPool(new JedisPoolConfig(), config.host(), config.port(), 300);
        this.ttlSeconds = ttlSeconds;
    }

    static String positionKey(String courierId) {
        return "courier:loc:" + courierId;
    }

    /** Канал заказа: шлюз подписан и пересылает позицию клиенту по WebSocket. */
    static String orderChannel(String orderId) {
        return "ws:order:" + orderId;
    }

    @Override
    public void save(String courierId, Position position) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("lat", Double.toString(position.latitude()));
        fields.put("lon", Double.toString(position.longitude()));
        fields.put("at", JsDates.format(position.at()));
        if (position.accuracy() != null) {
            fields.put("accuracy", Double.toString(position.accuracy()));
        }
        try (Jedis redis = pool.getResource()) {
            Transaction multi = redis.multi();
            multi.hset(positionKey(courierId), fields);
            multi.expire(positionKey(courierId), ttlSeconds);
            multi.exec();
        } catch (JedisException error) {
            LOG.warn("позиция не сохранена в кэш", "error", error.getMessage());
        }
    }

    @Override
    public Position get(String courierId) {
        try (Jedis redis = pool.getResource()) {
            Map<String, String> value = redis.hgetAll(positionKey(courierId));
            if (value.get("lat") == null) {
                return null;
            }
            String accuracy = value.get("accuracy");
            return new Position(Double.parseDouble(value.get("lat")), Double.parseDouble(value.get("lon")),
                    JsDates.parse(value.get("at")), accuracy == null ? null : Double.parseDouble(accuracy), null);
        } catch (JedisException error) {
            return null;
        }
    }

    @Override
    public void publish(String orderId, Object payload) {
        try (Jedis redis = pool.getResource()) {
            redis.publish(orderChannel(orderId), Json.write(payload));
        } catch (JedisException error) {
            LOG.warn("позиция не разослана", "error", error.getMessage());
        }
    }

    @Override
    public void close() {
        pool.close();
    }
}
