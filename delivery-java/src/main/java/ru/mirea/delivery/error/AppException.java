package ru.mirea.delivery.error;

import java.util.Map;

/**
 * Ошибка приложения: сообщение пользователю, а не сбой.
 *
 * Каждый подкласс знает свой код HTTP и код ошибки — обработчик запросов
 * переводит исключение в ответ единого формата
 * {@code {code, message, details, service, correlationId}}, одинакового
 * для всех сервисов системы. Всё, что не {@code AppException}, — сбой:
 * наружу уходит общее сообщение, подробности остаются в журнале.
 */
public abstract class AppException extends RuntimeException {
    private final Map<String, Object> details;

    protected AppException(String message, Map<String, Object> details) {
        super(message);
        this.details = details == null ? Map.of() : details;
    }

    public abstract int httpStatus();

    public abstract String code();

    /** Имеет ли смысл повторить операцию — оркестратор саги повторяет только такие. */
    public boolean retryable() {
        return false;
    }

    public Map<String, Object> details() {
        return details;
    }
}
