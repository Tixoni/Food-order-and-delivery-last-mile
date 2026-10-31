package ru.mirea.delivery.error;

import java.util.Map;

/**
 * Действие опоздало: предложение уже закрыто или просрочено, доставку уже
 * забрал другой курьер. Конфликт состояний, а не ошибка ввода — 409.
 */
public class ConflictException extends AppException {
    public ConflictException(String message) {
        this(message, null);
    }

    public ConflictException(String message, Map<String, Object> details) {
        super(message, details);
    }

    @Override
    public int httpStatus() {
        return 409;
    }

    @Override
    public String code() {
        return "CONFLICT";
    }
}
