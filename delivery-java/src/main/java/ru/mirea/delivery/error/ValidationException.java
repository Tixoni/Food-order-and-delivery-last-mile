package ru.mirea.delivery.error;

import java.util.Map;

/** Неверные входные данные — 400. */
public class ValidationException extends AppException {
    public ValidationException(String message) {
        this(message, null);
    }

    public ValidationException(String message, Map<String, Object> details) {
        super(message, details);
    }

    @Override
    public int httpStatus() {
        return 400;
    }

    @Override
    public String code() {
        return "VALIDATION_FAILED";
    }
}
