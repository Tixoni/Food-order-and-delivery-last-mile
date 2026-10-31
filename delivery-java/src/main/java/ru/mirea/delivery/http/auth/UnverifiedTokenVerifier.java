package ru.mirea.delivery.http.auth;

import ru.mirea.delivery.common.Json;
import ru.mirea.delivery.error.UnauthorizedException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Режим без провайдера входа: токен разбирается, но подпись не проверяется.
 * Допустимо только в тестах и на локальном стенде — в production сервис
 * с AUTH_DISABLED не запускается.
 */
public final class UnverifiedTokenVerifier implements TokenVerifier {
    @Override
    public AuthenticatedUser verify(String token) {
        String[] parts = token.split("\\.");
        if (parts.length < 2 || parts[1].isEmpty()) {
            throw new UnauthorizedException("токен имеет неверный формат");
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            if (!(Json.read(payload) instanceof Map<?, ?> claims)) {
                throw new UnauthorizedException("токен имеет неверный формат");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) claims;
            return TokenVerifier.toUser(typed);
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new UnauthorizedException("токен имеет неверный формат");
        }
    }
}
