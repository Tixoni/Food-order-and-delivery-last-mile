package ru.mirea.delivery.lastmile;

import java.util.List;

/** План курьера: заказы, маршрут после 2-opt, его оценка и длина жадного маршрута. */
public record PlannedRoute(String courier, String vehicle, List<String> orders, Route route,
                           double greedyDistance, RouteEvaluation evaluation, int passes) {
}
