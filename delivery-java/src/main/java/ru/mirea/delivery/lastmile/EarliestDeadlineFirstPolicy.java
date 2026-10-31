package ru.mirea.delivery.lastmile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Жадное распределение «по сроку».
 *
 * <ol>
 *   <li>Заказы сортируются по концу окна доставки — самые срочные первыми (EDF).</li>
 *   <li>Для заказа перебираются курьеры; каждому строится маршрут из уже
 *       назначенных заказов плюс нового (стратегией построения).</li>
 *   <li>Заказ получает курьер, у которого маршрут остаётся допустимым, без
 *       опозданий, и время в пути растёт меньше всего.</li>
 *   <li>Такого нет — заказ остаётся без курьера с причиной.</li>
 * </ol>
 *
 * Сложность: O(m log m) на сортировку и O(m · k · n²) на назначение
 * (m заказов, k курьеров, n ≤ 2m остановок в маршруте курьера).
 */
public final class EarliestDeadlineFirstPolicy implements AssignmentPolicy {
    private final RoutingStrategy routing;
    private final RouteEvaluator evaluator;

    public EarliestDeadlineFirstPolicy(RoutingStrategy routing, RouteEvaluator evaluator) {
        this.routing = routing;
        this.evaluator = evaluator;
    }

    private record Option(double added, double finish, FleetCourier courier, RouteEvaluation evaluation) {
        boolean betterThan(Option other) {
            return other == null || added < other.added || (added == other.added && finish < other.finish);
        }
    }

    @Override
    public Assignment assign(PlanningContext ctx) {
        Map<String, List<String>> assigned = new LinkedHashMap<>();
        Map<String, RouteEvaluation> evaluations = new LinkedHashMap<>();
        for (FleetCourier courier : ctx.couriers()) {
            assigned.put(courier.id(), new ArrayList<>());
            evaluations.put(courier.id(), evaluator.evaluate(Route.EMPTY, courier));
        }
        List<UnassignedOrder> unassigned = new ArrayList<>();

        List<PlanOrder> queue = new ArrayList<>(ctx.orders().values());
        queue.sort(Comparator.comparingDouble((PlanOrder order) -> order.window().end())
                .thenComparingDouble(PlanOrder::ready)
                .thenComparing(PlanOrder::id));
        for (PlanOrder order : queue) {
            Option best = null;
            for (FleetCourier courier : ctx.couriers()) {
                if (order.size() > courier.capacity()) {
                    continue;
                }
                List<String> candidate = new ArrayList<>(assigned.get(courier.id()));
                candidate.add(order.id());
                RouteEvaluation evaluation = evaluator.evaluate(routing.build(courier, candidate), courier);
                if (evaluation.visits().size() != 2 * candidate.size()) {
                    continue;
                }
                if (!evaluation.valid() || evaluation.lateness() > 0) {
                    continue;
                }
                Option option = new Option(evaluation.travel() - evaluations.get(courier.id()).travel(),
                        evaluation.finish(), courier, evaluation);
                if (option.betterThan(best)) {
                    best = option;
                }
            }
            if (best == null) {
                unassigned.add(new UnassignedOrder(order.id(), reason(order, ctx)));
                continue;
            }
            assigned.get(best.courier().id()).add(order.id());
            evaluations.put(best.courier().id(), best.evaluation());
        }
        return new Assignment(assigned, evaluations, unassigned);
    }

    /** Почему заказ не назначен ни одному курьеру. */
    static String reason(PlanOrder order, PlanningContext ctx) {
        if (ctx.couriers().stream().allMatch(courier -> order.size() > courier.capacity())) {
            return "перегруз: заказ больше вместимости любого курьера";
        }
        if (!ctx.matrix().reachable(order.pickupNode(), order.dropoffNode())) {
            return "точка вручения недостижима от точки выдачи";
        }
        if (ctx.couriers().stream().noneMatch(courier -> ctx.matrix().reachable(courier.node(), order.pickupNode()))) {
            return "точка выдачи недостижима ни для одного курьера";
        }
        return "ни один курьер не успевает в окно доставки";
    }
}
