package ru.mirea.delivery.lastmile;

import com.fasterxml.jackson.databind.JsonNode;
import ru.mirea.delivery.common.Json;
import ru.mirea.delivery.domain.Courier;
import ru.mirea.delivery.domain.CourierRecord;
import ru.mirea.delivery.domain.CourierStatus;
import ru.mirea.delivery.domain.GeoPoint;
import ru.mirea.delivery.domain.TimeWindow;
import ru.mirea.delivery.domain.VehicleProfile;
import ru.mirea.delivery.domain.VehicleType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Условие задачи планирования — тот же JSON, что читает Python-версия:
 *
 * <pre>
 * nodes     {id: [широта, долгота]}
 * edges     [[из, в, длина_м], [из, в, длина_м, "oneway"], ...]
 * vehicles  {тип: {"speed_kmh": .., "capacity": ..}}
 * service   {"pickup": мин, "dropoff": мин}
 * couriers  [{"id", "vehicle", "start": [ш, д], "shift_minutes"}]
 * orders    [{"id", "pickup": [ш, д], "dropoff": [ш, д], "ready": мин, "window": [с, по], "size": ед.}]
 * </pre>
 */
public final class PlanningProblem {
    private static final Map<String, VehicleProfile> DEFAULT_VEHICLES = Map.of(
            "foot", new VehicleProfile(5, 2),
            "bike", new VehicleProfile(14, 3),
            "car", new VehicleProfile(20, 6));
    private static final ServiceTimes DEFAULT_SERVICE = new ServiceTimes(2, 3);

    private final JsonNode root;

    public PlanningProblem(JsonNode root) {
        this.root = root;
    }

    public static PlanningProblem load(Path path) throws IOException {
        return new PlanningProblem(Json.mapper().readTree(Files.readString(path)));
    }

    public static PlanningProblem parse(String json) throws IOException {
        return new PlanningProblem(Json.mapper().readTree(json));
    }

    private Map<String, VehicleProfile> vehicles() {
        JsonNode node = root.get("vehicles");
        if (node == null || node.isEmpty()) {
            return DEFAULT_VEHICLES;
        }
        Map<String, VehicleProfile> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(field -> result.put(field.getKey(), new VehicleProfile(
                field.getValue().get("speed_kmh").asDouble(), field.getValue().get("capacity").asDouble())));
        return result;
    }

    private Iterable<JsonNode> list(String name) {
        JsonNode node = root.get(name);
        return node == null ? List.of() : node;
    }

    /** Ошибки данных; пустой список — данные годятся. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        Map<String, VehicleProfile> vehicles = vehicles();
        Set<String> seen = new HashSet<>();
        for (JsonNode courier : list("couriers")) {
            String id = courier.path("id").asText();
            String vehicle = courier.path("vehicle").asText(null);
            if (vehicle == null || !vehicles.containsKey(vehicle)) {
                problems.add("курьер " + id + ": неизвестный транспорт " + vehicle);
            }
            if (!seen.add(id)) {
                problems.add("повторяется идентификатор " + id);
            }
        }
        for (JsonNode order : list("orders")) {
            String id = order.path("id").asText();
            JsonNode window = order.get("window");
            if (window == null || window.size() != 2 || window.get(0).asDouble() > window.get(1).asDouble()) {
                problems.add("заказ " + id + ": окно доставки задано неверно");
            }
            JsonNode size = order.get("size");
            if (size == null || !size.isNumber() || size.asDouble() <= 0) {
                problems.add("заказ " + id + ": пустой заказ (размер должен быть больше 0)");
            }
            if (order.path("ready").asDouble(0) < 0) {
                problems.add("заказ " + id + ": время готовности раньше начала планирования");
            }
            if (!seen.add(id)) {
                problems.add("повторяется идентификатор " + id);
            }
        }
        return problems;
    }

    private static GeoPoint point(JsonNode pair) {
        return new GeoPoint(pair.get(0).asDouble(), pair.get(1).asDouble());
    }

    /**
     * Подготовка: граф O(V + E), привязка точек к узлам O((C + 2Z) · V),
     * матрица расстояний O(P · (V + E) log V).
     */
    public PlanningContext prepare() {
        List<String> errors = validate();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        Map<String, GeoPoint> nodes = new LinkedHashMap<>();
        root.get("nodes").fields().forEachRemaining(field -> nodes.put(field.getKey(), point(field.getValue())));
        Graph graph = new Graph(nodes);
        for (JsonNode edge : list("edges")) {
            boolean oneway = edge.size() > 3 && "oneway".equals(edge.get(3).asText());
            graph.addEdge(edge.get(0).asText(), edge.get(1).asText(), edge.get(2).asDouble(), oneway);
        }

        Map<String, VehicleProfile> vehicles = vehicles();
        List<FleetCourier> couriers = new ArrayList<>();
        for (JsonNode node : list("couriers")) {
            String id = node.get("id").asText();
            String vehicle = node.get("vehicle").asText();
            VehicleType type = VehicleType.fromWire(vehicle);
            if (type == null) {
                throw new IllegalArgumentException("курьер " + id + ": транспорт " + vehicle
                        + " не поддерживается (foot, bike, car)");
            }
            Courier courier = Courier.create(new CourierRecord(id, null, id, null, type, CourierStatus.OFFLINE,
                    null, true, null, null), vehicles.get(vehicle));
            couriers.add(new FleetCourier(id, vehicle, courier, graph.nearestNode(point(node.get("start"))),
                    node.path("shift_minutes").asDouble(240)));
        }
        Map<String, PlanOrder> orders = new LinkedHashMap<>();
        for (JsonNode node : list("orders")) {
            JsonNode window = node.get("window");
            orders.put(node.get("id").asText(), new PlanOrder(
                    node.get("id").asText(),
                    graph.nearestNode(point(node.get("pickup"))),
                    graph.nearestNode(point(node.get("dropoff"))),
                    node.path("ready").asDouble(0),
                    new TimeWindow(window.get(0).asDouble(), window.get(1).asDouble()),
                    node.get("size").asDouble()));
        }
        JsonNode service = root.get("service");
        ServiceTimes times = service == null || service.isEmpty() ? DEFAULT_SERVICE
                : new ServiceTimes(service.get("pickup").asDouble(), service.get("dropoff").asDouble());
        return PlanningContext.of(graph, couriers, orders, times);
    }
}
