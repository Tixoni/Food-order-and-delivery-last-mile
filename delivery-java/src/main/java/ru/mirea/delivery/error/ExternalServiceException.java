package ru.mirea.delivery.error;

import java.util.Map;

/** Внешняя система не ответила или ответила ошибкой — 502, повтор осмыслен. */
public class ExternalServiceException extends AppException {
    public ExternalServiceException(String message, Map<String, Object> details) {
        super(message, details);
    }

    @Override
    public int httpStatus() {
        return 502;
    }

    @Override
    public String code() {
        return "EXTERNAL_SERVICE_FAILED";
    }

    @Override
    public boolean retryable() {
        return true;
    }
}
