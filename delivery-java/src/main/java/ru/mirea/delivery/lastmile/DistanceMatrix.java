package ru.mirea.delivery.lastmile;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Расстояния по графу между всеми точками задачи. Дейкстра запускается по
 * разу из каждой точки — O(P · (V + E) log V); это дешевле Флойда — Уоршелла
 * O(V³), потому что точек задачи намного меньше, чем узлов графа.
 */
public final class DistanceMatrix {
    private final Map<String, Map<String, Double>> rows = new LinkedHashMap<>();

    public DistanceMatrix(Graph graph, List<String> points) {
        LinkedHashSet<String> unique = new LinkedHashSet<>(points);
        for (String source : unique) {
            ShortestPaths paths = Dijkstra.run(graph, source);
            Map<String, Double> row = new LinkedHashMap<>();
            unique.forEach(target -> row.put(target, paths.distanceTo(target)));
            rows.put(source, row);
        }
    }

    /** Метры от {@code from} до {@code to}; {@code +∞} — пути нет. */
    public double get(String from, String to) {
        Map<String, Double> row = rows.get(from);
        return row == null ? Double.POSITIVE_INFINITY : row.getOrDefault(to, Double.POSITIVE_INFINITY);
    }

    public boolean reachable(String from, String to) {
        return get(from, to) != Double.POSITIVE_INFINITY;
    }
}
