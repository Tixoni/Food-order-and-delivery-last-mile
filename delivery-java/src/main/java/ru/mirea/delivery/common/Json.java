package ru.mirea.delivery.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;

/**
 * Общий преобразователь JSON. Даты — строками в формате JavaScript,
 * пустые поля — {@code null}, а не пропуск: клиенты TS-версии их ждут.
 */
public final class Json {
    private static final ObjectMapper MAPPER = create();

    private Json() {
    }

    private static ObjectMapper create() {
        SimpleModule dates = new SimpleModule("js-dates");
        dates.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeString(JsDates.format(value));
            }
        });
        return new ObjectMapper().registerModule(dates);
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Разбор JSON в словари, списки, строки и числа. */
    public static Object read(String text) throws JsonProcessingException {
        return MAPPER.readValue(text, Object.class);
    }
}
