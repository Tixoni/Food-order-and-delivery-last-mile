package ru.mirea.delivery.lastmile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Подготовленная задача: граф, курьеры и заказы, привязанные к узлам,
 * время на точках и матрица расстояний между точками задачи.
 */
public record PlanningContext(Graph graph, DistanceMatrix matrix, List<FleetCourier> couriers,
                              Map<String, PlanOrder> orders, ServiceTimes service) {

    public static PlanningContext of(Graph graph, List<FleetCourier> couriers, Map<String, PlanOrder> orders,
                                     ServiceTimes service) {
        List<String> points = new ArrayList<>();
        couriers.forEach(courier -> points.add(courier.node()));
        orders.values().forEach(order -> {
            points.add(order.pickupNode());
            points.add(order.dropoffNode());
        });
        return new PlanningContext(graph, new DistanceMatrix(graph, points), couriers, orders, service);
    }

    public PlanOrder order(String id) {
        return orders.get(id);
    }
}
