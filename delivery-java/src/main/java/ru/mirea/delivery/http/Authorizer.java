package ru.mirea.delivery.http;

import ru.mirea.delivery.common.Maps;
import ru.mirea.delivery.error.ForbiddenException;
import ru.mirea.delivery.error.UnauthorizedException;
import ru.mirea.delivery.http.auth.AuthenticatedUser;
import ru.mirea.delivery.http.auth.InternalTokenGuard;
import ru.mirea.delivery.http.auth.TokenVerifier;

import java.util.Map;

/**
 * Проверка входа и прав на каждом запросе (SEC-2). Роль отвечает на
 * вопрос «имеет ли право вообще»; принадлежность объекта («это его
 * доставка?») проверяет сценарий.
 */
public final class Authorizer {
    private final TokenVerifier verifier;
    private final InternalTokenGuard internalToken;

    public Authorizer(TokenVerifier verifier, InternalTokenGuard internalToken) {
        this.verifier = verifier;
        this.internalToken = internalToken;
    }

    /** Пользователь запроса или {@code null}; исключение — если доступа нет. */
    public AuthenticatedUser authorize(Route route, Map<String, String> headers) {
        String token = bearer(headers.get("authorization"));
        if (route.access() != Access.USER) {
            // У открытого метода токен не обязателен, но если пришёл — разбирается.
            AuthenticatedUser user = null;
            if (token != null) {
                try {
                    user = verifier.verify(token);
                } catch (RuntimeException ignored) {
                    user = null;
                }
            }
            if (route.access() == Access.INTERNAL) {
                internalToken.check(headers.get(InternalTokenGuard.HEADER));
            }
            return user;
        }
        if (token == null) {
            throw new UnauthorizedException("требуется вход в систему");
        }
        AuthenticatedUser user = verifier.verify(token);
        if (!route.roles().isEmpty() && !user.hasAnyRole(route.roles())) {
            throw new ForbiddenException("недостаточно прав для этого действия",
                    Maps.of("required", route.roles(), "actual", user.roles()));
        }
        return user;
    }

    /** Токен — только из заголовка Authorization: строка запроса попадает в журналы целиком. */
    static String bearer(String header) {
        if (header == null) {
            return null;
        }
        String[] parts = header.split(" ");
        return parts.length >= 2 && parts[0].equalsIgnoreCase("bearer") && !parts[1].isEmpty() ? parts[1] : null;
    }
}
