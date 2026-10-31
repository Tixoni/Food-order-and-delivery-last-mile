package ru.mirea.delivery.lastmile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Маршрут курьера — неизменяемая последовательность остановок. */
public record Route(List<Waypoint> stops) {
    public static final Route EMPTY = new Route(List.of());

    public Route {
        stops = List.copyOf(stops);
    }

    public int size() {
        return stops.size();
    }

    /** Новый маршрут с развёрнутым участком {@code [i, j]} — ход 2-opt. */
    public Route reverseSegment(int i, int j) {
        List<Waypoint> result = new ArrayList<>(stops.subList(0, i));
        List<Waypoint> segment = new ArrayList<>(stops.subList(i, j + 1));
        Collections.reverse(segment);
        result.addAll(segment);
        result.addAll(stops.subList(j + 1, stops.size()));
        return new Route(result);
    }
}
