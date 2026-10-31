package ru.mirea.delivery.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ru.mirea.delivery.common.CorrelationContext;
import ru.mirea.delivery.common.Json;
import ru.mirea.delivery.common.Log;
import ru.mirea.delivery.common.Maps;
import ru.mirea.delivery.error.AppException;
import ru.mirea.delivery.error.ValidationException;
import ru.mirea.delivery.http.auth.AuthenticatedUser;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP API сервиса. Маршруты, коды ответов, проверка прав и формат ошибок
 * те же, что у TS-версии, поэтому шлюз и {@code order} работают с сервисом
 * без изменений.
 *
 * Обработка запроса ({@link #handle}) не зависит от HTTP-сервера — её
 * проверяют тесты без сети; {@link #start} лишь связывает её с сервером JDK.
 */
public final class HttpApi {
    public static final String SERVICE = "delivery";
    private static final Log LOG = Log.of("http");

    private final Router router = new Router();
    private final Authorizer authorizer;
    private final Metrics metrics;
    private HttpServer server;
    private ExecutorService executor;

    public HttpApi(List<Controller> controllers, Authorizer authorizer, Metrics metrics) {
        this.authorizer = authorizer;
        this.metrics = metrics;
        controllers.forEach(controller -> controller.register(router));
    }

    /** Ответ на запрос. Заголовки — с именами в нижнем регистре. */
    public ApiResponse handle(String method, String rawPath, Map<String, String> headers, byte[] body) {
        String path = rawPath.length() > 1 && rawPath.endsWith("/") ? rawPath.replaceAll("/+$", "") : rawPath;
        String correlationId = CorrelationContext.accept(headers.get(CorrelationContext.HEADER));
        return CorrelationContext.call(correlationId, () -> {
            long started = System.nanoTime();
            Router.Match match = router.match(method, path);
            ApiResponse response = match == null
                    ? ApiResponse.json(404, Maps.of("message", "Cannot " + method + " " + path,
                    "error", "Not Found", "statusCode", 404, "service", SERVICE, "correlationId", correlationId))
                    : dispatch(match, method, headers, body, correlationId);
            double seconds = (System.nanoTime() - started) / 1e9;
            String route = match == null ? "unmatched" : match.route().path();
            metrics.record(method, route, response.status(), seconds);
            boolean quiet = List.of("/health", "/ready", "/metrics").contains(route);
            LOG.write(quiet ? Log.Level.DEBUG : response.status() >= 500 ? Log.Level.WARN : Log.Level.INFO,
                    "запрос обработан", "method", method, "route", route, "status", response.status(),
                    "durationMs", Math.round(seconds * 10_000) / 10.0);
            return response;
        });
    }

    private ApiResponse dispatch(Router.Match match, String method, Map<String, String> headers, byte[] body,
                                 String correlationId) {
        Route route = match.route();
        try {
            AuthenticatedUser user = authorizer.authorize(route, headers);
            Object parsed = method.equals("POST") ? parseBody(headers.get("content-type"), body) : Map.of();
            Object result = route.handler().handle(new ApiRequest(match.params(), parsed, user));
            return result instanceof ApiResponse response ? response : ApiResponse.json(route.status(), result);
        } catch (AppException error) {
            return ApiResponse.json(error.httpStatus(), Maps.of("code", error.code(), "message", error.getMessage(),
                    "details", error.details(), "service", SERVICE, "correlationId", correlationId));
        } catch (RuntimeException failure) {
            // Сбой: подробности — в журнал, наружу — общее сообщение.
            StringWriter stack = new StringWriter();
            failure.printStackTrace(new PrintWriter(stack));
            LOG.error("необработанная ошибка", "error", failure.toString(), "stack", stack.toString());
            return ApiResponse.json(500, Maps.of("code", "INTERNAL", "message", "внутренняя ошибка сервиса",
                    "service", SERVICE, "correlationId", correlationId));
        }
    }

    /** Тело JSON. Пустое или не JSON по типу — пустой объект, как у Express. */
    static Object parseBody(String contentType, byte[] body) {
        if (body == null || body.length == 0 || contentType == null || !contentType.contains("json")) {
            return Map.of();
        }
        try {
            return Json.read(new String(body, StandardCharsets.UTF_8));
        } catch (JsonProcessingException error) {
            throw new ValidationException("тело запроса — не JSON");
        }
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 128);
        executor = Executors.newFixedThreadPool(16);
        server.setExecutor(executor);
        server.createContext("/", this::exchange);
        server.start();
    }

    private void exchange(HttpExchange exchange) throws IOException {
        try (exchange) {
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name.toLowerCase(), values.get(0));
                }
            });
            byte[] body = exchange.getRequestBody().readAllBytes();
            ApiResponse response = handle(exchange.getRequestMethod().toUpperCase(),
                    exchange.getRequestURI().getPath(), headers, body);
            byte[] payload = response.body() instanceof String text
                    ? text.getBytes(StandardCharsets.UTF_8)
                    : Json.write(response.body()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            String correlationId = CorrelationContext.accept(headers.get(CorrelationContext.HEADER));
            exchange.getResponseHeaders().set(CorrelationContext.HEADER, correlationId);
            exchange.sendResponseHeaders(response.status(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            executor.shutdown();
        }
    }
}
