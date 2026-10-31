package ru.mirea.delivery.lastmile;

import java.util.List;

/** Результат планирования: маршруты, заказы без курьера, итоги и время расчёта по этапам. */
public record Plan(List<PlannedRoute> routes, List<UnassignedOrder> unassigned, Totals totals, Timings timings) {

    public record Totals(int orders, int assigned, int unassigned, double distanceM, double greedyDistanceM,
                         double latenessMin, int graphNodes, int graphEdges) {
    }

    public record Timings(double prepareMs, double assignMs, double improveMs) {
    }
}
