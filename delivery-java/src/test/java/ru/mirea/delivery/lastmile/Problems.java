package ru.mirea.delivery.lastmile;

import java.io.IOException;

/**
 * Маленькие задачи для тестов: улица из четырёх перекрёстков a–b–c–d
 * по 1 км (ребро c→d одностороннее) и отдельный двор z без дорог.
 */
final class Problems {
    private Problems() {
    }

    static final String NODES = """
            "nodes": {"a": [55.75, 37.60], "b": [55.75, 37.616], "c": [55.75, 37.632], "d": [55.75, 37.648],
                      "z": [55.80, 37.70]},
            "edges": [["a","b",1000], ["b","c",1000], ["c","d",1000,"oneway"]]""";

    static PlanningProblem of(String couriers, String orders) {
        try {
            return PlanningProblem.parse("{" + NODES + ", \"couriers\": [" + couriers + "], \"orders\": [" + orders + "]}");
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    static String courier(String id, String vehicle, String at) {
        return "{\"id\":\"%s\",\"vehicle\":\"%s\",\"start\":%s,\"shift_minutes\":240}".formatted(id, vehicle, at);
    }

    static String order(String id, String from, String to, double ready, double start, double end, double size) {
        return "{\"id\":\"%s\",\"pickup\":%s,\"dropoff\":%s,\"ready\":%s,\"window\":[%s,%s],\"size\":%s}"
                .formatted(id, from, to, ready, start, end, size);
    }

    static final String A = "[55.75, 37.60]";
    static final String B = "[55.75, 37.616]";
    static final String C = "[55.75, 37.632]";
    static final String D = "[55.75, 37.648]";
    static final String Z = "[55.80, 37.70]";
}
