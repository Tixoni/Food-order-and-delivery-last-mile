package ru.mirea.delivery.http.controller;

import ru.mirea.delivery.common.Maps;
import ru.mirea.delivery.http.Access;
import ru.mirea.delivery.http.ApiResponse;
import ru.mirea.delivery.http.Controller;
import ru.mirea.delivery.http.HttpApi;
import ru.mirea.delivery.http.Metrics;
import ru.mirea.delivery.http.Router;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Две разные проверки: /health — «процесс жив» (по ней перезапускают
 * контейнер), /ready — «готов принимать запросы» (база отвечает). Обе и
 * /metrics открыты без входа: их спрашивает оркестратор, а не человек.
 */
public final class HealthController implements Controller {
    private final long startedNanos = System.nanoTime();
    private final Supplier<Map<String, Object>> databaseCheck;
    private final Metrics metrics;

    public HealthController(Supplier<Map<String, Object>> databaseCheck, Metrics metrics) {
        this.databaseCheck = databaseCheck;
        this.metrics = metrics;
    }

    @Override
    public void register(Router router) {
        router.get("/health", Access.PUBLIC, List.of(), request -> Maps.of("status", "ok", "service",
                HttpApi.SERVICE, "uptimeSec", Math.round((System.nanoTime() - startedNanos) / 1e9)));
        router.get("/ready", Access.PUBLIC, List.of(), request -> {
            Map<String, Object> checks = Maps.of("database", databaseCheck.get());
            boolean failed = checks.values().stream().anyMatch(check -> "fail".equals(((Map<?, ?>) check).get("status")));
            return failed
                    ? ApiResponse.json(503, Maps.of("status", "not_ready", "service", HttpApi.SERVICE, "checks", checks))
                    : Maps.of("status", "ready", "service", HttpApi.SERVICE, "checks", checks);
        });
        router.get("/metrics", Access.PUBLIC, List.of(), request -> ApiResponse.text(200, metrics.render()));
    }
}
