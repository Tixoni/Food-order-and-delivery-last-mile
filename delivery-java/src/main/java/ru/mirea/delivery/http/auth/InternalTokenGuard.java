package ru.mirea.delivery.http.auth;

import ru.mirea.delivery.error.UnauthorizedException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Служебный токен внутреннего API: запрос и освобождение курьера — только
 * от {@code order}. Сравнение за постоянное время: по времени ответа токен
 * не подобрать посимвольно.
 */
public final class InternalTokenGuard {
    public static final String HEADER = "x-internal-token";

    private final byte[] expected;

    public InternalTokenGuard(String token) {
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    public void check(String presented) {
        byte[] actual = (presented == null ? "" : presented).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(actual, expected)) {
            throw new UnauthorizedException("внутренний API: неверный служебный токен");
        }
    }
}
