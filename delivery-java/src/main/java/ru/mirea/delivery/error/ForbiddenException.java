package ru.mirea.delivery.error;

import java.util.Map;

/** Вход выполнен, но прав на действие нет — 403. */
public class ForbiddenException extends AppException {
    public ForbiddenException(String message) {
        this(message, null);
    }

    public ForbiddenException(String message, Map<String, Object> details) {
        super(message, details);
    }

    @Override
    public int httpStatus() {
        return 403;
    }

    @Override
    public String code() {
        return "FORBIDDEN";
    }
}
