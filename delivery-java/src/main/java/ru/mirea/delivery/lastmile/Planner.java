package ru.mirea.delivery.lastmile;

import java.util.ArrayList;
import java.util.List;

/**
 * Полный расчёт плана:
 * <ol>
 *   <li>подготовка — граф, привязка точек, матрица расстояний (Дейкстра);</li>
 *   <li>распределение — {@link AssignmentPolicy} (по умолчанию «по сроку»);</li>
 *   <li>улучшение — 2-opt поверх жадного маршрута каждого курьера;</li>
 *   <li>итоги — путь, опоздание, сколько сэкономил 2-opt.</li>
 * </ol>
 */
public final class Planner {

    public Plan plan(PlanningProblem problem) {
        long started = System.nanoTime();
        PlanningContext ctx = problem.prepare();
        double prepareMs = (System.nanoTime() - started) / 1e6;

        RouteEvaluator evaluator = new RouteEvaluator(ctx);
        GreedyStrategy greedy = new GreedyStrategy(ctx);
        TwoOptStrategy twoOpt = new TwoOptStrategy(greedy, evaluator);
        AssignmentPolicy policy = new EarliestDeadlineFirstPolicy(greedy, evaluator);

        started = System.nanoTime();
        Assignment assignment = policy.assign(ctx);
        double assignMs = (System.nanoTime() - started) / 1e6;

        started = System.nanoTime();
        List<PlannedRoute> routes = new ArrayList<>();
        for (FleetCourier courier : ctx.couriers()) {
            List<String> orders = assignment.assigned().get(courier.id());
            Route greedyRoute = greedy.build(courier, orders);
            double greedyDistance = evaluator.evaluate(greedyRoute, courier).distance();
            TwoOptStrategy.Improvement improved = twoOpt.improve(greedyRoute, courier);
            routes.add(new PlannedRoute(courier.id(), courier.vehicle(), orders, improved.route(), greedyDistance,
                    improved.evaluation(), improved.passes()));
        }
        double improveMs = (System.nanoTime() - started) / 1e6;

        Plan.Totals totals = new Plan.Totals(
                ctx.orders().size(),
                routes.stream().mapToInt(route -> route.orders().size()).sum(),
                assignment.unassigned().size(),
                routes.stream().mapToDouble(route -> route.evaluation().distance()).sum(),
                routes.stream().mapToDouble(PlannedRoute::greedyDistance).sum(),
                routes.stream().mapToDouble(route -> route.evaluation().lateness()).sum(),
                ctx.graph().nodeCount(),
                ctx.graph().directedEdgeCount());
        return new Plan(routes, assignment.unassigned(), totals, new Plan.Timings(prepareMs, assignMs, improveMs));
    }
}
