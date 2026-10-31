package ru.mirea.delivery.http;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.mirea.delivery.app.DispatchService;
import ru.mirea.delivery.app.TrackingService;
import ru.mirea.delivery.common.Json;
import ru.mirea.delivery.config.AppConfig;
import ru.mirea.delivery.http.auth.InternalTokenGuard;
import ru.mirea.delivery.http.auth.UnverifiedTokenVerifier;
import ru.mirea.delivery.http.controller.AdminCouriersController;
import ru.mirea.delivery.http.controller.CourierController;
import ru.mirea.delivery.http.controller.HealthController;
import ru.mirea.delivery.http.controller.InternalDeliveryController;
import ru.mirea.delivery.http.controller.TrackingController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Слой HTTP без базы: проверки прав, формат ошибок и разбор тела срабатывают раньше хранилища. */
class HttpApiTest {
    private static HttpApi api;

    @BeforeAll
    static void build() {
        AppConfig.Dispatch settings = new AppConfig.Dispatch(3000, 90, 120, 1000);
        DispatchService dispatch = new DispatchService(null, null, settings, Clock.systemUTC());
        TrackingService tracking = new TrackingService(null, null, null);
        Metrics metrics = new Metrics("delivery");
        api = new HttpApi(List.of(
                new HealthController(() -> Map.of("status", "ok"), metrics),
                new InternalDeliveryController(dispatch),
                new CourierController(null),
                new TrackingController(tracking),
                new AdminCouriersController(tracking)),
                new Authorizer(new UnverifiedTokenVerifier(), new InternalTokenGuard("secret")), metrics);
    }

    private static String token(String... roles) {
        String payload = Json.write(Map.of("sub", "u1", "realm_access", Map.of("roles", List.of(roles))));
        return "e30." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".x";
    }

    private static Map<?, ?> call(int expected, String method, String path, Map<String, String> headers, String body) {
        Map<String, String> all = new HashMap<>(headers);
        all.put("content-type", "application/json");
        ApiResponse response = api.handle(method, path, all, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, response.status(), () -> Json.write(response.body()));
        return (Map<?, ?>) response.body();
    }

    @Test
    void healthIsPublic() {
        assertEquals("ok", call(200, "GET", "/health", Map.of(), null).get("status"));
    }

    @Test
    void unknownRouteLooksLikeNest() {
        assertEquals("Cannot GET /nope", call(404, "GET", "/nope", Map.of(), null).get("message"));
    }

    @Test
    void internalApiNeedsServiceToken() {
        Map<?, ?> body = call(401, "POST", "/deliveries", Map.of(), "{}");
        assertEquals("UNAUTHORIZED", body.get("code"));
        assertEquals("delivery", body.get("service"));
        assertEquals("VALIDATION_FAILED",
                call(400, "POST", "/deliveries", Map.of("x-internal-token", "secret"), "{}").get("code"));
    }

    @Test
    void userRoutesNeedTokenAndRole() {
        assertEquals("требуется вход в систему", call(401, "GET", "/couriers/me", Map.of(), null).get("message"));
        Map<?, ?> body = call(403, "GET", "/couriers/me", Map.of("authorization", "Bearer " + token("client")), null);
        assertEquals(List.of("courier"), ((Map<?, ?>) body.get("details")).get("required"));
    }

    @Test
    void bodyIsValidated() {
        Map<String, String> courier = Map.of("authorization", "Bearer " + token("courier"));
        assertEquals("onShift: true или false",
                call(400, "POST", "/couriers/me/shift", courier, "{\"onShift\":1}").get("message"));
        Map<String, String> admin = Map.of("authorization", "Bearer " + token("admin"));
        assertEquals("тело запроса — не JSON", call(400, "POST", "/couriers", admin, "{bad").get("message"));
    }

    @Test
    void correlationIdIsEchoed() {
        Map<?, ?> body = call(404, "GET", "/x", Map.of("x-correlation-id", "abc-1"), null);
        assertEquals("abc-1", body.get("correlationId"));
    }
}
