package ru.mirea.delivery.error;

import java.util.Map;

/** Объект не найден — 404. */
public class NotFoundException extends AppException {
    public NotFoundException(String message) {
        this(message, null);
    }

    public NotFoundException(String message, Map<String, Object> details) {
        super(message, details);
    }

    @Override
    public int httpStatus() {
        return 404;
    }

    @Override
    public String code() {
        return "NOT_FOUND";
    }
}
