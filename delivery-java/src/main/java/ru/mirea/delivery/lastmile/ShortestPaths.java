package ru.mirea.delivery.lastmile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Кратчайшие расстояния от одного узла и предки для восстановления путей. */
public record ShortestPaths(String source, Map<String, Double> distances, Map<String, String> previous) {

    /** Расстояние до узла; {@code +∞} — узел недостижим. */
    public double distanceTo(String node) {
        return distances.getOrDefault(node, Double.POSITIVE_INFINITY);
    }

    /** Путь от источника до узла, O(V); пустой — узел недостижим. */
    public List<String> pathTo(String target) {
        if (!distances.containsKey(target)) {
            return List.of();
        }
        List<String> path = new ArrayList<>();
        for (String node = target; node != null; node = previous.get(node)) {
            path.add(node);
        }
        Collections.reverse(path);
        return path;
    }
}
