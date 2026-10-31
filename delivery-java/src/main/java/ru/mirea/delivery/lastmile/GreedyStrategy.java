package ru.mirea.delivery.lastmile;

import java.util.ArrayList;
import java.util.List;

/**
 * Жадное построение — «ближайший сосед» по времени начала обслуживания
 * (вариант Соломона для задач с окнами), O(n²).
 *
 * На каждом шаге кандидаты — забор любого ещё не взятого заказа (если
 * хватает вместимости) и вручение любого заказа на руках. Выбирается тот,
 * где обслуживание начнётся раньше всех — с учётом дороги, готовности еды
 * и начала окна клиента; при равенстве — у кого раньше конец окна, затем
 * первый по порядку (заборы перебираются раньше вручений).
 */
public final class GreedyStrategy implements RoutingStrategy {
    private final PlanningContext ctx;

    public GreedyStrategy(PlanningContext ctx) {
        this.ctx = ctx;
    }

    private record Choice(double start, double windowEnd, PlanOrder order, StopKind kind) {
        boolean betterThan(Choice other) {
            return other == null || start < other.start
                    || (start == other.start && windowEnd < other.windowEnd);
        }
    }

    @Override
    public Route build(FleetCourier courier, List<String> orderIds) {
        String node = courier.node();
        double now = 0;
        double load = 0;
        List<String> waiting = new ArrayList<>(orderIds);
        List<String> onboard = new ArrayList<>();
        List<Waypoint> stops = new ArrayList<>();

        while (!waiting.isEmpty() || !onboard.isEmpty()) {
            Choice best = null;
            for (String id : waiting) {
                PlanOrder order = ctx.order(id);
                if (load + order.size() > courier.capacity()) {
                    continue;
                }
                double meters = ctx.matrix().get(node, order.pickupNode());
                if (meters == Double.POSITIVE_INFINITY) {
                    continue;
                }
                Choice choice = new Choice(Math.max(now + courier.travelMinutes(meters), order.ready()),
                        order.window().end(), order, StopKind.PICKUP);
                if (choice.betterThan(best)) {
                    best = choice;
                }
            }
            for (String id : onboard) {
                PlanOrder order = ctx.order(id);
                double meters = ctx.matrix().get(node, order.dropoffNode());
                if (meters == Double.POSITIVE_INFINITY) {
                    continue;
                }
                Choice choice = new Choice(Math.max(now + courier.travelMinutes(meters), order.window().start()),
                        order.window().end(), order, StopKind.DROPOFF);
                if (choice.betterThan(best)) {
                    best = choice;
                }
            }
            if (best == null) {
                // Остаток не взять: перегруз или недостижимые точки — оценка маршрута это покажет.
                break;
            }
            PlanOrder order = best.order();
            if (best.kind() == StopKind.PICKUP) {
                stops.add(Waypoint.pickup(order));
                node = order.pickupNode();
                waiting.remove(order.id());
                onboard.add(order.id());
                load += order.size();
                now = best.start() + ctx.service().pickup();
            } else {
                stops.add(Waypoint.dropoff(order));
                node = order.dropoffNode();
                onboard.remove(order.id());
                load -= order.size();
                now = best.start() + ctx.service().dropoff();
            }
        }
        return new Route(stops);
    }
}
