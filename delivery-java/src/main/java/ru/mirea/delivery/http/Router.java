package ru.mirea.delivery.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/** Таблица маршрутов: контроллеры регистрируют свои, запрос ищет подходящий. */
public final class Router {
    public record Match(Route route, Map<String, String> params) {
    }

    private final List<Route> routes = new ArrayList<>();

    public Router get(String path, Access access, List<String> roles, RouteHandler handler) {
        return add(Route.of("GET", path, access, roles, handler, 200));
    }

    public Router post(String path, Access access, List<String> roles, RouteHandler handler) {
        return add(Route.of("POST", path, access, roles, handler, 200));
    }

    public Router post(String path, Access access, List<String> roles, RouteHandler handler, int status) {
        return add(Route.of("POST", path, access, roles, handler, status));
    }

    public Router add(Route route) {
        routes.add(route);
        return this;
    }

    public Match match(String method, String path) {
        for (Route route : routes) {
            Matcher matcher = route.pattern().matcher(path);
            if (matcher.matches() && route.method().equals(method)) {
                Map<String, String> params = new LinkedHashMap<>();
                for (String name : route.pattern().namedGroups().keySet()) {
                    params.put(name, matcher.group(name));
                }
                return new Match(route, params);
            }
        }
        return null;
    }
}
