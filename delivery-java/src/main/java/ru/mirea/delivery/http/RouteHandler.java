package ru.mirea.delivery.http;

/** Обработчик маршрута: возвращает тело ответа или готовый {@link ApiResponse}. */
@FunctionalInterface
public interface RouteHandler {
    Object handle(ApiRequest request);
}
