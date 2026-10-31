package ru.mirea.delivery.common;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Сквозной идентификатор действия (OBS-1).
 *
 * Запрос обрабатывается в одном потоке от начала до конца, поэтому
 * идентификатор хранится в {@link ThreadLocal}: он доступен в любой точке
 * обработки и попадает в журнал и события, не передаваясь параметром.
 */
public final class CorrelationContext {
    public static final String HEADER = "x-correlation-id";

    /** Тот же формат, что принимает TS-версия: колонка VARCHAR(100) и заголовки брокера. */
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationContext() {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Идентификатор от вызывающего — если он безопасного вида, иначе новый. */
    public static String accept(String incoming) {
        return incoming != null && ACCEPTED.matcher(incoming).matches() ? incoming : newId();
    }

    /** Идентификатор текущего действия или {@code null} вне действия. */
    public static String current() {
        return CURRENT.get();
    }

    /** Идентификатор текущего действия; вне действия — новый, чтобы запись не осталась без привязки. */
    public static String getOrNew() {
        String id = CURRENT.get();
        return id != null ? id : newId();
    }

    public static <T> T call(String correlationId, Supplier<T> work) {
        String previous = CURRENT.get();
        CURRENT.set(correlationId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
