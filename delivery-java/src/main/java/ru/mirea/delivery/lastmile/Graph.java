package ru.mirea.delivery.lastmile;

import ru.mirea.delivery.domain.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Граф улиц: узлы-перекрёстки с координатами и рёбра с длиной в метрах.
 * Рёбра бывают односторонними, поэтому расстояние «туда» и «обратно»
 * может различаться. Сложность построения — O(V + E).
 */
public final class Graph {
    public record Edge(String to, double length) {
    }

    private final Map<String, GeoPoint> nodes;
    private final Map<String, List<Edge>> adjacency = new LinkedHashMap<>();

    public Graph(Map<String, GeoPoint> nodes) {
        this.nodes = new LinkedHashMap<>(nodes);
        nodes.keySet().forEach(node -> adjacency.put(node, new ArrayList<>()));
    }

    /** Ребро {@code from → to}; двустороннее добавляется в обе стороны. */
    public void addEdge(String from, String to, double length, boolean oneway) {
        if (!adjacency.containsKey(from) || !adjacency.containsKey(to)) {
            throw new IllegalArgumentException("ребро " + from + "–" + to + " ссылается на неизвестный узел");
        }
        if (length < 0) {
            throw new IllegalArgumentException("ребро " + from + "–" + to + ": отрицательная длина");
        }
        adjacency.get(from).add(new Edge(to, length));
        if (!oneway) {
            adjacency.get(to).add(new Edge(from, length));
        }
    }

    public List<Edge> edgesFrom(String node) {
        return Collections.unmodifiableList(adjacency.get(node));
    }

    public int nodeCount() {
        return adjacency.size();
    }

    public int directedEdgeCount() {
        return adjacency.values().stream().mapToInt(List::size).sum();
    }

    /** Ближайший к точке узел — «привязка» адреса к улице, O(V). При равенстве — первый по порядку. */
    public String nearestNode(GeoPoint point) {
        String best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Map.Entry<String, GeoPoint> node : nodes.entrySet()) {
            double distance = node.getValue().distanceTo(point);
            if (distance < bestDistance) {
                best = node.getKey();
                bestDistance = distance;
            }
        }
        return best;
    }
}
