package ru.mirea.delivery.lastmile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static ru.mirea.delivery.lastmile.Problems.A;
import static ru.mirea.delivery.lastmile.Problems.B;
import static ru.mirea.delivery.lastmile.Problems.C;
import static ru.mirea.delivery.lastmile.Problems.Z;
import static ru.mirea.delivery.lastmile.Problems.courier;
import static ru.mirea.delivery.lastmile.Problems.of;
import static ru.mirea.delivery.lastmile.Problems.order;

class AssignmentTest {
    private static Assignment assign(String couriers, String... orders) {
        PlanningContext ctx = of(couriers, String.join(",", orders)).prepare();
        RouteEvaluator evaluator = new RouteEvaluator(ctx);
        return new EarliestDeadlineFirstPolicy(new GreedyStrategy(ctx), evaluator).assign(ctx);
    }

    @Test
    void feasibleOrdersAreAssigned() {
        Assignment result = assign(courier("k1", "bike", A) + "," + courier("k2", "car", C),
                order("o1", B, C, 0, 0, 60, 1), order("o2", C, B, 0, 0, 60, 1));
        assertEquals(List.of(), result.unassigned());
        assertEquals(2, result.assigned().values().stream().mapToInt(List::size).sum());
    }

    @Test
    void edgeCasesGetReasons() {
        Assignment result = assign(courier("k1", "car", A),
                order("big", B, C, 0, 0, 120, 10),
                order("island", B, Z, 0, 0, 120, 1),
                order("tight", B, C, 0, 0, 0.5, 1),
                order("ok", B, C, 0, 0, 120, 1));
        assertEquals(List.of(
                new UnassignedOrder("tight", "ни один курьер не успевает в окно доставки"),
                new UnassignedOrder("big", "перегруз: заказ больше вместимости любого курьера"),
                new UnassignedOrder("island", "точка вручения недостижима от точки выдачи")), result.unassigned());
        assertEquals(List.of("ok"), result.assigned().get("k1"));
    }

    @Test
    void noOrdersNoRoutes() {
        Assignment result = assign(courier("k1", "foot", A));
        assertEquals(List.of(), result.assigned().get("k1"));
    }
}
