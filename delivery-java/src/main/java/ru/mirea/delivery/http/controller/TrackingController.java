package ru.mirea.delivery.http.controller;

import ru.mirea.delivery.app.TrackingService;
import ru.mirea.delivery.http.Access;
import ru.mirea.delivery.http.Controller;
import ru.mirea.delivery.http.Router;
import ru.mirea.delivery.http.auth.AuthenticatedUser;

import java.util.List;

/** Позиция курьера для клиента заказа и оператора (TRK-2, SEC-7). */
public final class TrackingController implements Controller {
    private final TrackingService tracking;

    public TrackingController(TrackingService tracking) {
        this.tracking = tracking;
    }

    @Override
    public void register(Router router) {
        router.get("/orders/{orderId}/courier-position", Access.USER, List.of("client", "operator", "admin"),
                request -> {
                    AuthenticatedUser user = request.requireUser();
                    return tracking.courierPosition(request.param("orderId"), user.id(), user.roles());
                });
    }
}
