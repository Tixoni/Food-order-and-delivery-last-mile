package ru.mirea.delivery.common;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Упорядоченный словарь из пар «ключ, значение». В отличие от {@code Map.of}
 * допускает {@code null} и сохраняет порядок полей — ответы API совпадают
 * с TS-версией поле в поле.
 */
public final class Maps {
    private Maps() {
    }

    public static Map<String, Object> of(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
