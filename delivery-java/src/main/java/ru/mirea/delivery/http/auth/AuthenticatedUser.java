package ru.mirea.delivery.http.auth;

import java.util.List;

/** Кто выполняет запрос: идентификатор учётной записи и роли из проверенного токена. */
public record AuthenticatedUser(String id, List<String> roles, String email, String name, String restaurantId) {
    public boolean hasAnyRole(List<String> required) {
        return required.stream().anyMatch(roles::contains);
    }
}
