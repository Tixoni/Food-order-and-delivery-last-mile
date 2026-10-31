package ru.mirea.delivery.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Настройки из переменных окружения — те же имена и значения по умолчанию,
 * что у TS- и Python-версий, поэтому сервис встаёт на место {@code delivery}
 * в docker-compose без правок. Проверка — при запуске: лучше не стартовать,
 * чем работать с неверной настройкой.
 */
public record AppConfig(
        int port,
        String nodeEnv,
        String logLevel,
        Database database,
        Redis redis,
        Kafka kafka,
        Outbox outbox,
        Auth auth,
        String internalToken,
        Dispatch dispatch,
        Routing routing,
        Locations locations) {

    public static final String DEV_INTERNAL_TOKEN = "delivery-internal-dev-token";

    public record Database(String host, int port, String user, String password, String name) {
        public String jdbcUrl() {
            return "jdbc:postgresql://" + host + ":" + port + "/" + name + "?ApplicationName=delivery-java";
        }
    }

    public record Redis(String host, int port) {
    }

    public record Kafka(List<String> brokers, String clientId) {
    }

    public record Outbox(int pollIntervalMs, int batchSize) {
    }

    public record Auth(String issuer, String providerUrl, String audience, boolean disabled) {
    }

    /**
     * @param radiusMeters    DLV-3: радиус поиска от точки выдачи
     * @param offerTtlSeconds DLV-4: срок отклика на предложение
     * @param freshSeconds    позиция старше — курьер считается потерявшим связь
     */
    public record Dispatch(int radiusMeters, int offerTtlSeconds, int freshSeconds, int sweepIntervalMs) {
    }

    /** Маршрутизатор OSRM (DLV-6); {@code url == null} — подбор и прогноз по прямой (REL-2). */
    public record Routing(String url, int timeoutMs) {
    }

    public record Locations(int positionTtlSeconds, int flushIntervalMs, int maxBuffer) {
    }

    public static AppConfig fromEnv(Map<String, String> env) {
        Env e = new Env(env);
        String level = e.get("LOG_LEVEL", "info");
        if (!List.of("debug", "info", "warn", "error").contains(level)) {
            throw new IllegalStateException("недопустимое значение LOG_LEVEL: " + level);
        }
        String nodeEnv = e.get("NODE_ENV", "development");
        boolean authDisabled = "true".equals(env.get("AUTH_DISABLED"));
        if (nodeEnv.equals("production") && authDisabled) {
            throw new IllegalStateException("AUTH_DISABLED запрещён в production");
        }
        String internalToken = e.required("DELIVERY_INTERNAL_TOKEN", DEV_INTERNAL_TOKEN);
        if (nodeEnv.equals("production") && internalToken.equals(DEV_INTERNAL_TOKEN)
                && !"true".equals(env.get("ALLOW_DEV_SECRETS"))) {
            throw new IllegalStateException("в production нужен собственный DELIVERY_INTERNAL_TOKEN "
                    + "(для учебного стенда — ALLOW_DEV_SECRETS=true)");
        }
        String osrm = e.get("OSRM_URL", "http://localhost:15000").trim();
        String providerUrl = env.get("KEYCLOAK_INTERNAL_URL");

        return new AppConfig(
                e.numeric("DELIVERY_PORT", 3004),
                nodeEnv,
                level,
                new Database(e.required("POSTGRES_HOST", "localhost"), e.numeric("POSTGRES_PORT", 15432),
                        e.required("POSTGRES_USER", "app"), e.required("POSTGRES_PASSWORD", "app"),
                        e.required("DELIVERY_DB", "delivery_db")),
                new Redis(e.required("REDIS_HOST", "localhost"), e.numeric("REDIS_PORT", 16379)),
                new Kafka(Arrays.stream(e.required("KAFKA_BROKERS", "localhost:19092").split(","))
                        .map(String::trim).filter(broker -> !broker.isEmpty()).toList(),
                        e.get("KAFKA_CLIENT_ID", "delivery")),
                new Outbox(e.numeric("OUTBOX_POLL_INTERVAL_MS", 500), e.numeric("OUTBOX_BATCH_SIZE", 100)),
                new Auth(e.required("KEYCLOAK_ISSUER", "http://localhost:18080/realms/food-delivery"),
                        providerUrl == null || providerUrl.isEmpty() ? null : providerUrl,
                        e.required("AUTH_AUDIENCE", "food-delivery-api"), authDisabled),
                internalToken,
                new Dispatch(e.numeric("DISPATCH_RADIUS_METERS", 3000), e.numeric("OFFER_TTL_SECONDS", 90),
                        e.numeric("COURIER_FRESH_SECONDS", 120), e.numeric("OFFER_SWEEP_INTERVAL_MS", 1000)),
                // Пустая строка — маршрутизатор выключен намеренно.
                new Routing(osrm.isEmpty() ? null : osrm, e.numeric("OSRM_TIMEOUT_MS", 500)),
                new Locations(e.numeric("POSITION_TTL_SECONDS", 60), e.numeric("LOCATION_FLUSH_INTERVAL_MS", 3000),
                        e.numeric("LOCATION_MAX_BUFFER", 10_000)));
    }

    /** Чтение переменных с проверкой — отдельный маленький помощник, чтобы не повторять проверки. */
    private record Env(Map<String, String> values) {
        String get(String name, String fallback) {
            String value = values.get(name);
            return value == null ? fallback : value;
        }

        String required(String name, String fallback) {
            String value = get(name, fallback);
            if (value == null || value.isEmpty()) {
                throw new IllegalStateException("не задана обязательная переменная окружения " + name);
            }
            return value;
        }

        int numeric(String name, int fallback) {
            String raw = values.get(name);
            if (raw == null || raw.isEmpty()) {
                return fallback;
            }
            try {
                int value = Integer.parseInt(raw);
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // ниже — общее сообщение
            }
            throw new IllegalStateException(
                    "переменная окружения " + name + " должна быть целым положительным числом");
        }
    }
}
