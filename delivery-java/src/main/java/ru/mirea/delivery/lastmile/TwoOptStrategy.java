package ru.mirea.delivery.lastmile;

import java.util.List;

/**
 * Локальное улучшение 2-opt поверх другой стратегии: разворот участка
 * маршрута между позициями i и j. Перестановка принимается, если маршрут
 * остаётся допустимым и становится лучше (меньше опоздание, затем короче).
 * Проходы повторяются, пока есть улучшение, но не больше {@code maxPasses}.
 *
 * Проход стоит O(n³): O(n²) пар (i, j), каждая оценивается за O(n). В задаче
 * коммивояжёра выигрыш хода считается за O(1), но здесь окна и порядок
 * «забрал → вручил» сдвигают время по всему хвосту маршрута.
 */
public final class TwoOptStrategy implements RoutingStrategy {
    /** Улучшенный маршрут, его оценка и число проходов. */
    public record Improvement(Route route, RouteEvaluation evaluation, int passes) {
    }

    private final RoutingStrategy initial;
    private final RouteEvaluator evaluator;
    private final int maxPasses;

    public TwoOptStrategy(RoutingStrategy initial, RouteEvaluator evaluator, int maxPasses) {
        this.initial = initial;
        this.evaluator = evaluator;
        this.maxPasses = maxPasses;
    }

    public TwoOptStrategy(RoutingStrategy initial, RouteEvaluator evaluator) {
        this(initial, evaluator, 20);
    }

    @Override
    public Route build(FleetCourier courier, List<String> orderIds) {
        return improve(initial.build(courier, orderIds), courier).route();
    }

    public Improvement improve(Route start, FleetCourier courier) {
        Route best = start;
        RouteEvaluation bestEvaluation = evaluator.evaluate(best, courier);
        boolean improved = true;
        int passes = 0;
        while (improved && passes < maxPasses) {
            improved = false;
            passes++;
            int size = best.size();
            for (int i = 0; i < size - 1; i++) {
                for (int j = i + 1; j < size; j++) {
                    Route candidate = best.reverseSegment(i, j);
                    RouteEvaluation evaluation = evaluator.evaluate(candidate, courier);
                    if (evaluation.valid() && evaluation.key().compareTo(bestEvaluation.key()) < 0) {
                        best = candidate;
                        bestEvaluation = evaluation;
                        improved = true;
                    }
                }
            }
        }
        return new Improvement(best, bestEvaluation, passes);
    }
}
