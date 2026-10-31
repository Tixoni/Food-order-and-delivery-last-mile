package ru.mirea.delivery.http;

import ru.mirea.delivery.error.UnauthorizedException;
import ru.mirea.delivery.http.auth.AuthenticatedUser;

import java.util.Map;

/** Разобранный запрос: параметры пути, тело JSON и пользователь (если вошёл). */
public record ApiRequest(Map<String, String> params, Object body, AuthenticatedUser user) {
    public String param(String name) {
        return params.get(name);
    }

    /** Пользователь, без которого действие невозможно. */
    public AuthenticatedUser requireUser() {
        if (user == null) {
            throw new UnauthorizedException("требуется вход в систему");
        }
        return user;
    }

    public Map<?, ?> bodyMap() {
        return body instanceof Map<?, ?> map ? map : Map.of();
    }
}
