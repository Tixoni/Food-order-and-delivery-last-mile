package ru.mirea.delivery.infra.routing;

import com.fasterxml.jackson.databind.JsonNode;
import ru.mirea.delivery.common.Json;
import ru.mirea.delivery.common.Maps;
import ru.mirea.delivery.domain.GeoPoint;
import ru.mirea.delivery.error.ExternalServiceException;
import ru.mirea.delivery.port.RoadRoute;
import ru.mirea.delivery.port.RoutePlanner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Маршрутизатор на OSRM (DLV-6) за размыкателем цепи (REL-2).
 *
 * Отказом считаются только сеть, таймаут и ответ 5xx. Три отказа подряд
 * размыкают цепь на 15 с: подбор в это время идёт по прямой и не ждёт
 * таймаута на каждом заказе. Ответ «дороги нет» — штатный, а не отказ.
 */
public final class OsrmRoutePlanner implements RoutePlanner {
    private static final Set<String> NO_ROUTE = Set.of("NoRoute", "NoSegment", "NoTable");

    private final String url;
    private final Duration timeout;
    private final HttpClient http;
    private final CircuitBreaker breaker = new CircuitBreaker("routing", 3, 15_000);

    public OsrmRoutePlanner(String url, int timeoutMs) {
        this.url = url.replaceAll("/+$", "");
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public CircuitBreaker.State circuitState() {
        return breaker.state();
    }

    private static String coordinate(GeoPoint point) {
        return String.format(Locale.ROOT, "%.6f,%.6f", point.longitude(), point.latitude());
    }

    @Override
    public List<Double> roadDistances(List<GeoPoint> from, GeoPoint to) {
        if (from.isEmpty()) {
            return List.of();
        }
        // Точка 0 — цель, остальные — источники: одна строка матрицы на курьера.
        List<GeoPoint> points = new ArrayList<>();
        points.add(to);
        points.addAll(from);
        String coordinates = points.stream().map(OsrmRoutePlanner::coordinate).collect(Collectors.joining(";"));
        String sources = IntStream.rangeClosed(1, from.size()).mapToObj(String::valueOf)
                .collect(Collectors.joining(";"));
        JsonNode body = call("/table/v1/driving/" + coordinates + "?sources=" + sources
                + "&destinations=0&annotations=distance");
        List<Double> result = new ArrayList<>();
        JsonNode distances = body == null ? null : body.get("distances");
        for (int i = 0; i < from.size(); i++) {
            JsonNode row = distances == null ? null : distances.get(i);
            JsonNode value = row == null ? null : row.get(0);
            result.add(value != null && value.isNumber() ? value.asDouble() : null);
        }
        return result;
    }

    @Override
    public RoadRoute route(GeoPoint from, GeoPoint to) {
        JsonNode body = call("/route/v1/driving/" + coordinate(from) + ";" + coordinate(to)
                + "?overview=simplified&geometries=geojson&alternatives=false&steps=false");
        JsonNode routes = body == null ? null : body.get("routes");
        if (routes == null || routes.isEmpty()) {
            return null;
        }
        JsonNode found = routes.get(0);
        List<GeoPoint> path = new ArrayList<>();
        for (JsonNode coordinate : found.get("geometry").get("coordinates")) {
            path.add(new GeoPoint(coordinate.get(1).asDouble(), coordinate.get(0).asDouble()));
        }
        return new RoadRoute(Math.round(found.get("distance").asDouble()), path);
    }

    /** Ответ OSRM; {@code null} — дороги нет. */
    private JsonNode call(String path) {
        if (!breaker.allows()) {
            throw new ExternalServiceException("маршрутизатор: цепь разомкнута", Maps.of("dependency", "osrm"));
        }
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(url + path)).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException error) {
            breaker.failure();
            throw new ExternalServiceException("маршрутизатор не ответил",
                    Maps.of("dependency", "osrm", "reason", error.getClass().getSimpleName()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceException("маршрутизатор не ответил", Maps.of("dependency", "osrm"));
        }
        if (response.statusCode() >= 500) {
            breaker.failure();
            throw new ExternalServiceException("маршрутизатор ответил " + response.statusCode(),
                    Maps.of("dependency", "osrm"));
        }
        breaker.success();
        JsonNode body;
        try {
            body = Json.mapper().readTree(response.body());
        } catch (IOException notJson) {
            body = null;
        }
        String code = body != null && body.has("code") ? body.get("code").asText() : null;
        if ("Ok".equals(code)) {
            return body;
        }
        if (code != null && NO_ROUTE.contains(code)) {
            return null;
        }
        // Ошибка запроса — наша, а не движка: цепь из-за неё не размыкается.
        throw new IllegalStateException("маршрутизатор отклонил запрос: "
                + (code != null ? code : response.statusCode()));
    }
}
