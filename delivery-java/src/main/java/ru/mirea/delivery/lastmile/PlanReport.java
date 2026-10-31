package ru.mirea.delivery.lastmile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Текстовый отчёт о плане — строка в строку как у Python-версии, чтобы
 * результаты двух реализаций сравнивались простым сравнением текста
 * (кроме строки со временем расчёта).
 */
public final class PlanReport {
    private PlanReport() {
    }

    /** Число с {@code digits} знаками — округление как в Python: точное значение, к ближайшему чётному. */
    public static String fixed(double value, int digits) {
        return new BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** Целое — без дробной части, как печатает Python числа из JSON. */
    public static String number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : Double.toString(value);
    }

    static String clock(double minutes) {
        long whole = (long) minutes;
        return String.format("+%d:%02d", whole / 60, whole % 60);
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    public static String format(Plan plan) {
        List<String> lines = new ArrayList<>();
        Plan.Totals totals = plan.totals();
        lines.add("Граф: " + totals.graphNodes() + " узлов, " + totals.graphEdges() + " направленных рёбер. "
                + "Заказов: " + totals.orders() + ", распределено: " + totals.assigned()
                + ", без курьера: " + totals.unassigned() + ".");
        for (PlannedRoute route : plan.routes()) {
            RouteEvaluation evaluation = route.evaluation();
            lines.add("");
            lines.add("Курьер " + route.courier() + " (" + route.vehicle() + "): заказов " + route.orders().size()
                    + ", путь " + fixed(evaluation.distance() / 1000, 2) + " км"
                    + " (жадный " + fixed(route.greedyDistance() / 1000, 2) + " км)"
                    + ", финиш " + clock(evaluation.finish()) + ", опоздание " + fixed(evaluation.lateness(), 0)
                    + " мин, проходов 2-opt: " + route.passes());
            for (Visit visit : evaluation.visits()) {
                String action = visit.kind() == StopKind.PICKUP ? "забрать " : "вручить ";
                double wait = visit.start() - visit.arrival();
                lines.add("    " + clock(visit.arrival()) + "  " + action + pad(visit.order(), 6) + " "
                        + "узел " + pad(visit.node(), 8)
                        + (wait >= 1 ? " ожидание " + fixed(wait, 0) + " мин" : ""));
            }
        }
        if (!plan.unassigned().isEmpty()) {
            lines.add("");
            lines.add("Без курьера:");
            plan.unassigned().forEach(item -> lines.add("    " + item.order() + ": " + item.reason()));
        }
        lines.add("");
        double saved = totals.greedyDistanceM() - totals.distanceM();
        lines.add("Итого: " + fixed(totals.distanceM() / 1000, 2) + " км, 2-opt сократил путь на "
                + fixed(saved / 1000, 2) + " км, опоздание " + fixed(totals.latenessMin(), 0) + " мин.");
        Plan.Timings timings = plan.timings();
        lines.add("Время расчёта: подготовка " + fixed(timings.prepareMs(), 1) + " мс, распределение "
                + fixed(timings.assignMs(), 1) + " мс, улучшение " + fixed(timings.improveMs(), 1) + " мс.");
        return String.join("\n", lines);
    }
}
