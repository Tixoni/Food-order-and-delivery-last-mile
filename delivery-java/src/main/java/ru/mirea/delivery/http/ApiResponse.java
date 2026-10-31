package ru.mirea.delivery.http;

/** Ответ: код, тело (объект для JSON или строка для текста) и тип содержимого. */
public record ApiResponse(int status, Object body, String contentType) {
    public static final String JSON = "application/json; charset=utf-8";
    public static final String TEXT = "text/plain; version=0.0.4; charset=utf-8";

    public static ApiResponse json(int status, Object body) {
        return new ApiResponse(status, body, JSON);
    }

    public static ApiResponse text(int status, String body) {
        return new ApiResponse(status, body, TEXT);
    }
}
