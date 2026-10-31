package ru.mirea.delivery.lastmile;

/** Остановка маршрута: заказ, действие и узел графа. */
public record Waypoint(String orderId, StopKind kind, String node) {
    public static Waypoint pickup(PlanOrder order) {
        return new Waypoint(order.id(), StopKind.PICKUP, order.pickupNode());
    }

    public static Waypoint dropoff(PlanOrder order) {
        return new Waypoint(order.id(), StopKind.DROPOFF, order.dropoffNode());
    }
}
