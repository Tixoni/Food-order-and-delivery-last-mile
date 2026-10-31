package ru.mirea.delivery.lastmile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.mirea.delivery.lastmile.Problems.A;
import static ru.mirea.delivery.lastmile.Problems.B;
import static ru.mirea.delivery.lastmile.Problems.C;
import static ru.mirea.delivery.lastmile.Problems.courier;
import static ru.mirea.delivery.lastmile.Problems.of;
import static ru.mirea.delivery.lastmile.Problems.order;

class RoutesTest {
    private static PlanningContext ctx(String vehicle, String... orders) {
        return of(courier("k", vehicle, A), String.join(",", orders)).prepare();
    }

    @Test
    void dropoffBeforePickupIsInvalid() {
        PlanningContext ctx = ctx("bike", order("o1", B, C, 0, 0, 100, 1));
        PlanOrder o1 = ctx.order("o1");
        RouteEvaluation evaluation = new RouteEvaluator(ctx).evaluate(
                new Route(List.of(Waypoint.dropoff(o1), Waypoint.pickup(o1))), ctx.couriers().get(0));
        assertFalse(evaluation.valid());
        assertTrue(evaluation.problems().contains("o1: вручение раньше забора"));
    }

    @Test
    void overloadIsInvalid() {
        PlanningContext ctx = ctx("foot", order("o1", B, C, 0, 0, 500, 2), order("o2", B, C, 0, 0, 500, 1));
        PlanOrder o1 = ctx.order("o1");
        PlanOrder o2 = ctx.order("o2");
        RouteEvaluation evaluation = new RouteEvaluator(ctx).evaluate(new Route(List.of(
                Waypoint.pickup(o1), Waypoint.pickup(o2), Waypoint.dropoff(o1), Waypoint.dropoff(o2))),
                ctx.couriers().get(0));
        assertEquals(List.of("o2: перегруз (3 > 2)"), evaluation.problems());
    }

    @Test
    void lateArrivalCountsLateness() {
        // Велосипед, 14 км/ч: 1 км ≈ 4.29 мин, к b, забор 2 мин, ещё 1 км до c — окно до 5 мин.
        PlanningContext ctx = ctx("bike", order("o1", B, C, 0, 0, 5, 1));
        PlanOrder o1 = ctx.order("o1");
        RouteEvaluation evaluation = new RouteEvaluator(ctx).evaluate(
                new Route(List.of(Waypoint.pickup(o1), Waypoint.dropoff(o1))), ctx.couriers().get(0));
        assertTrue(evaluation.valid());
        assertEquals(2 * (60.0 / 14) + 2 - 5, evaluation.lateness(), 1e-9);
    }

    @Test
    void greedyVisitsEveryStopOncePickupFirst() {
        PlanningContext ctx = ctx("car", order("o1", B, C, 0, 0, 200, 1), order("o2", C, B, 0, 0, 200, 1),
                order("o3", A, C, 0, 0, 200, 1));
        Route route = new GreedyStrategy(ctx).build(ctx.couriers().get(0), List.of("o1", "o2", "o3"));
        assertEquals(6, route.size());
        RouteEvaluation evaluation = new RouteEvaluator(ctx).evaluate(route, ctx.couriers().get(0));
        assertTrue(evaluation.valid(), evaluation.problems().toString());
    }

    @Test
    void twoOptIsNeverWorse() {
        PlanningContext ctx = ctx("car", order("o1", C, A, 0, 0, 300, 1), order("o2", B, C, 0, 0, 300, 1),
                order("o3", C, B, 0, 0, 300, 1), order("o4", A, C, 0, 0, 300, 1));
        RouteEvaluator evaluator = new RouteEvaluator(ctx);
        FleetCourier courier = ctx.couriers().get(0);
        GreedyStrategy greedy = new GreedyStrategy(ctx);
        Route start = greedy.build(courier, List.of("o1", "o2", "o3", "o4"));
        TwoOptStrategy.Improvement improved = new TwoOptStrategy(greedy, evaluator).improve(start, courier);
        assertTrue(improved.evaluation().valid());
        assertTrue(improved.evaluation().key().compareTo(evaluator.evaluate(start, courier).key()) <= 0);
    }
}
