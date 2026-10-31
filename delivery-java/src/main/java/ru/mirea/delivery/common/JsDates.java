package ru.mirea.delivery.common;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Даты в формате JavaScript {@code Date.toJSON()}: 2026-10-31T09:15:00.123Z.
 * Так даты отдаёт TS-версия сервиса, так их ждут шлюз и {@code order}.
 */
public final class JsDates {
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private JsDates() {
    }

    public static String format(Instant moment) {
        return moment == null ? null : FORMAT.format(moment);
    }

    /** Дата из строки ISO 8601; {@code null} — строка не дата. */
    public static Instant parse(Object text) {
        if (!(text instanceof String value) || value.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException offsetMissing) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException notDate) {
                return null;
            }
        }
    }
}
