package ru.mirea.delivery.app;

import java.util.regex.Pattern;

/** Проверка идентификаторов из запроса: всё, что уходит в колонки UUID, проверяется заранее. */
public final class Ids {
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private Ids() {
    }

    public static boolean isUuid(Object value) {
        return value instanceof String text && UUID.matcher(text).matches();
    }
}
