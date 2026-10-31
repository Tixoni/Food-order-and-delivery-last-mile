package ru.mirea.delivery.lastmile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Проигрывание маршрута во времени, O(n). Курьер выходит из своей точки
 * в момент 0; на точке выдачи ждёт готовности заказа, на точке вручения —
 * начала окна клиента. Ограничения: каждый заказ забирается и вручается
 * ровно раз, забор раньше вручения, не больше вместимости на руках, все
 * точки достижимы, маршрут укладывается в смену.
 */
public final class RouteEvaluator {
    private static final double EPS = 1e-9;

    private final PlanningContext ctx;

    public RouteEvaluator(PlanningContext ctx) {
        this.ctx = ctx;
    }

    public RouteEvaluation evaluate(Route route, FleetCourier courier) {
        String node = courier.node();
        double now = 0;
        double load = 0;
        double distance = 0;
        double travel = 0;
        double lateness = 0;
        Set<String> picked = new HashSet<>();
        Set<String> onboard = new HashSet<>();
        List<Visit> visits = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        for (Waypoint stop : route.stops()) {
            PlanOrder order = ctx.order(stop.orderId());
            double meters = ctx.matrix().get(node, stop.node());
            if (meters == Double.POSITIVE_INFINITY) {
                problems.add(order.id() + ": точка недостижима");
                break;
            }
            double minutes = courier.travelMinutes(meters);
            distance += meters;
            travel += minutes;
            now += minutes;
            double arrival = now;
            double started;
            if (stop.kind() == StopKind.PICKUP) {
                if (picked.contains(order.id())) {
                    problems.add(order.id() + ": забран дважды");
                }
                now = Math.max(now, order.ready());
                load += order.size();
                if (load > courier.capacity()) {
                    problems.add(order.id() + ": перегруз (" + PlanReport.number(load) + " > "
                            + PlanReport.number(courier.capacity()) + ")");
                }
                picked.add(order.id());
                onboard.add(order.id());
                started = now;
                now += ctx.service().pickup();
            } else {
                if (!onboard.contains(order.id())) {
                    problems.add(order.id() + ": вручение раньше забора");
                }
                now = order.window().serviceStart(now);
                lateness += order.window().lateness(now);
                load -= order.size();
                onboard.remove(order.id());
                started = now;
                now += ctx.service().dropoff();
            }
            visits.add(new Visit(order.id(), stop.kind(), stop.node(), arrival, started, now));
            node = stop.node();
        }
        if (!onboard.isEmpty() && problems.isEmpty()) {
            problems.add("не вручены: " + String.join(", ", new TreeSet<>(onboard)));
        }
        if (now > courier.shiftMinutes() + EPS) {
            problems.add("маршрут дольше смены (" + PlanReport.fixed(now, 0) + " > "
                    + PlanReport.number(courier.shiftMinutes()) + " мин)");
        }
        return new RouteEvaluation(problems.isEmpty(), lateness, distance, travel, now, visits, problems);
    }
}
