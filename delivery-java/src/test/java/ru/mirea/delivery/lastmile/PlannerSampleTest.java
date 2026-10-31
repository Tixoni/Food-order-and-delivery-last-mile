package ru.mirea.delivery.lastmile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Задача из data/moscow_sample.json: итоги те же, что у Python-версии. */
class PlannerSampleTest {
    private static final Path SAMPLE = Path.of("..", "data", "moscow_sample.json");

    @Test
    void sampleMatchesProceduralVersion() throws Exception {
        Assumptions.assumeTrue(Files.exists(SAMPLE), "нет файла задачи " + SAMPLE);
        Plan plan = new Planner().plan(PlanningProblem.load(SAMPLE));
        assertEquals(20, plan.totals().orders());
        assertEquals(18, plan.totals().assigned());
        assertEquals("67.05", PlanReport.fixed(plan.totals().distanceM() / 1000, 2));
        assertEquals("71.45", PlanReport.fixed(plan.totals().greedyDistanceM() / 1000, 2));
        assertEquals(0, plan.totals().latenessMin(), 1e-9);
        assertEquals(List.of("Z19", "Z20"), plan.unassigned().stream().map(UnassignedOrder::order).toList());
    }
}
