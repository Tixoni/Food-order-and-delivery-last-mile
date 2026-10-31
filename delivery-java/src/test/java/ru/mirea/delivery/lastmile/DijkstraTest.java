package ru.mirea.delivery.lastmile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static ru.mirea.delivery.lastmile.Problems.of;

class DijkstraTest {
    private final Graph graph = of("", "").prepare().graph();

    @Test
    void shortestDistancesAndPath() {
        ShortestPaths paths = Dijkstra.run(graph, "a");
        assertEquals(3000, paths.distanceTo("d"));
        assertEquals(List.of("a", "b", "c", "d"), paths.pathTo("d"));
    }

    @Test
    void onewayEdgeWorksOneDirection() {
        assertEquals(Double.POSITIVE_INFINITY, Dijkstra.run(graph, "d").distanceTo("a"));
        assertEquals(2000, Dijkstra.run(graph, "c").distanceTo("a"));
    }

    @Test
    void isolatedNodeIsUnreachable() {
        ShortestPaths paths = Dijkstra.run(graph, "a");
        assertEquals(List.of(), paths.pathTo("z"));
        assertFalse(new DistanceMatrix(graph, List.of("a", "z")).reachable("a", "z"));
    }

    @Test
    void shorterDetourWins() {
        Graph g = new Graph(new java.util.LinkedHashMap<>(java.util.Map.of(
                "a", new ru.mirea.delivery.domain.GeoPoint(0, 0), "b", new ru.mirea.delivery.domain.GeoPoint(0, 1),
                "c", new ru.mirea.delivery.domain.GeoPoint(1, 1))));
        g.addEdge("a", "c", 500, false);
        g.addEdge("a", "b", 100, false);
        g.addEdge("b", "c", 100, false);
        assertEquals(200, Dijkstra.run(g, "a").distanceTo("c"));
        assertEquals(List.of("a", "b", "c"), Dijkstra.run(g, "a").pathTo("c"));
    }
}
