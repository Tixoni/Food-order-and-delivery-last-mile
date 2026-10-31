package ru.mirea.delivery.lastmile;

import java.util.List;
import java.util.Map;

/** Итог распределения: заказы каждого курьера, оценки их маршрутов и нераспределённые заказы. */
public record Assignment(Map<String, List<String>> assigned, Map<String, RouteEvaluation> evaluations,
                         List<UnassignedOrder> unassigned) {
}
