package ru.mirea.delivery.lastmile;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Алгоритм Дейкстры на двоичной куче — O((V + E) log V).
 *
 * Из кучи достаётся узел с наименьшей оценкой; устаревшие записи (узел
 * уже закреплён) пропускаются — так обходится без операции «уменьшить
 * ключ». При равных оценках раньше достаётся узел с меньшим именем — тот
 * же порядок, что у кучи Python-версии.
 */
public final class Dijkstra {
    private record Entry(double distance, String node) {
    }

    private Dijkstra() {
    }

    public static ShortestPaths run(Graph graph, String source) {
        Map<String, Double> distances = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        Set<String> done = new HashSet<>();
        PriorityQueue<Entry> heap = new PriorityQueue<>((a, b) -> {
            int byDistance = Double.compare(a.distance(), b.distance());
            return byDistance != 0 ? byDistance : a.node().compareTo(b.node());
        });
        distances.put(source, 0.0);
        previous.put(source, null);
        heap.add(new Entry(0.0, source));
        while (!heap.isEmpty()) {
            Entry current = heap.poll();
            if (!done.add(current.node())) {
                continue;
            }
            for (Graph.Edge edge : graph.edgesFrom(current.node())) {
                double candidate = current.distance() + edge.length();
                if (candidate < distances.getOrDefault(edge.to(), Double.POSITIVE_INFINITY)) {
                    distances.put(edge.to(), candidate);
                    previous.put(edge.to(), current.node());
                    heap.add(new Entry(candidate, edge.to()));
                }
            }
        }
        return new ShortestPaths(source, distances, previous);
    }
}
