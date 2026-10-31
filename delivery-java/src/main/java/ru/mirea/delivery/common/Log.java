package ru.mirea.delivery.common;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Журнал — JSON-строки в стандартный вывод, поля те же, что у TS- и
 * Python-версий: Alloy собирает их в Loki (OBS-1). Телефон, адрес,
 * токены и пароли маскируются.
 *
 * Журнал привязан к компоненту: {@code Log.of("outbox").warn(...)}.
 */
public final class Log {
    public enum Level {
        DEBUG(10), INFO(20), WARN(30), ERROR(40);

        private final int order;

        Level(int order) {
            this.order = order;
        }

        public static Level parse(String name) {
            return Level.valueOf(name.toUpperCase());
        }
    }

    private static final List<String> SENSITIVE = List.of(
            "password", "passwd", "secret", "token", "accesstoken", "refreshtoken",
            "authorization", "apikey", "cardnumber", "pan", "cvv", "cvc", "expiry",
            "phone", "email", "address");
    private static final int MAX_DEPTH = 6;
    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private static volatile String service = "delivery";
    private static volatile Level minimum = Level.INFO;

    private final String component;

    private Log(String component) {
        this.component = component;
    }

    public static Log of(String component) {
        return new Log(component);
    }

    public static Log root() {
        return new Log(null);
    }

    public static void configure(String serviceName, Level level) {
        service = serviceName;
        minimum = level;
    }

    public void debug(String message, Object... fields) {
        write(Level.DEBUG, message, fields);
    }

    public void info(String message, Object... fields) {
        write(Level.INFO, message, fields);
    }

    public void warn(String message, Object... fields) {
        write(Level.WARN, message, fields);
    }

    public void error(String message, Object... fields) {
        write(Level.ERROR, message, fields);
    }

    /** Поля — парами «имя, значение». */
    public void write(Level level, String message, Object... fields) {
        if (level.order < minimum.order) {
            return;
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", JsDates.format(Instant.now()));
        record.put("level", level.name().toLowerCase());
        record.put("service", service);
        record.put("message", message);
        String correlationId = CorrelationContext.current();
        if (correlationId != null) {
            record.put("correlationId", correlationId);
        }
        if (component != null) {
            record.put("component", component);
        }
        for (int i = 0; i + 1 < fields.length; i += 2) {
            String key = String.valueOf(fields[i]);
            record.put(key, isSensitive(key) ? "***" : mask(fields[i + 1], 0));
        }
        String line = Json.write(record);
        synchronized (OUT) {
            OUT.println(line);
        }
    }

    static boolean isSensitive(String key) {
        String normalized = key.toLowerCase().replace("_", "").replace("-", "");
        return SENSITIVE.stream().anyMatch(normalized::contains);
    }

    private static Object mask(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return "[глубина превышена]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> masked = new LinkedHashMap<>();
            map.forEach((key, item) -> masked.put(String.valueOf(key),
                    isSensitive(String.valueOf(key)) ? "***" : mask(item, depth + 1)));
            return masked;
        }
        if (value instanceof List<?> list) {
            List<Object> masked = new ArrayList<>();
            list.forEach(item -> masked.add(mask(item, depth + 1)));
            return masked;
        }
        return value;
    }
}
