package ru.mirea.delivery.http.controller;

import ru.mirea.delivery.app.TrackingService;
import ru.mirea.delivery.http.Access;
import ru.mirea.delivery.http.Controller;
import ru.mirea.delivery.http.Router;

import java.util.List;

/** ADM-2: курьеры заводятся администратором. */
public final class AdminCouriersController implements Controller {
    private static final List<String> ADMIN = List.of("admin");

    private final TrackingService tracking;

    public AdminCouriersController(TrackingService tracking) {
        this.tracking = tracking;
    }

    @Override
    public void register(Router router) {
        router.get("/couriers", Access.USER, ADMIN, request -> tracking.listCouriers());
        router.post("/couriers", Access.USER, ADMIN, request -> tracking.registerCourier(request.body()), 201);
    }
}
