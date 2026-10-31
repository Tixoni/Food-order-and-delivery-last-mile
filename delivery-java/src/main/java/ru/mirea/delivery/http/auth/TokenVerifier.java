package ru.mirea.delivery.http.auth;

import ru.mirea.delivery.error.UnauthorizedException;

import java.util.List;
import java.util.Map;

/**
 * Проверка токена доступа. Две реализации: по ключам Keycloak (рабочий
 * режим) и без проверки подписи (AUTH_DISABLED — только тесты и стенд).
 */
public interface TokenVerifier {
    /** Роли системы; служебные роли провайдера отбрасываются. */
    List<String> ROLES = List.of("client", "manager", "kitchen", "courier", "operator", "admin");

    /** @throws UnauthorizedException токен недействителен */
    AuthenticatedUser verify(String token);

    /** Пользователь из полей токена — общее для обеих реализаций. */
    static AuthenticatedUser toUser(Map<String, Object> claims) {
        Object sub = claims.get("sub");
        if (!(sub instanceof String id) || id.isEmpty()) {
            throw new UnauthorizedException("в токене нет идентификатора пользователя");
        }
        List<String> roles = List.of();
        if (claims.get("realm_access") instanceof Map<?, ?> realm && realm.get("roles") instanceof List<?> list) {
            roles = list.stream().filter(ROLES::contains).map(String::valueOf).toList();
        }
        Object name = claims.get("name") != null ? claims.get("name") : claims.get("preferred_username");
        return new AuthenticatedUser(id, roles, text(claims.get("email")), text(name),
                text(claims.get("restaurantId")));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
