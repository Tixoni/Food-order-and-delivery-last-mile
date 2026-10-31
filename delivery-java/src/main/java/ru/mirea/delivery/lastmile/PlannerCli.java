package ru.mirea.delivery.lastmile;

import ru.mirea.delivery.common.Json;
import ru.mirea.delivery.common.Maps;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Планирование из командной строки:
 * <pre>
 * java -jar delivery-java-all.jar plan ../data/moscow_sample.json
 * java -jar delivery-java-all.jar plan ../data/moscow_sample.json --json
 * </pre>
 */
public final class PlannerCli {
    private PlannerCli() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    public static int run(String[] args) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        if (args.length == 0) {
            out.println("укажите файл задачи: plan задача.json [--json]");
            return 2;
        }
        Plan plan = new Planner().plan(PlanningProblem.load(Path.of(args[0])));
        if (List.of(args).contains("--json")) {
            out.println(Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(toJson(plan)));
        } else {
            out.println(PlanReport.format(plan));
        }
        return 0;
    }

    static Map<String, Object> toJson(Plan plan) {
        return Maps.of(
                "routes", plan.routes().stream().map(route -> Maps.of(
                        "courier", route.courier(),
                        "vehicle", route.vehicle(),
                        "orders", route.orders(),
                        "stops", route.route().stops().stream().map(stop -> Maps.of(
                                "order", stop.orderId(), "kind", stop.kind().wire(), "node", stop.node())).toList(),
                        "greedy_distance", route.greedyDistance(),
                        "evaluation", route.evaluation(),
                        "passes", route.passes())).toList(),
                "unassigned", plan.unassigned(),
                "totals", plan.totals(),
                "timings", plan.timings());
    }
}
