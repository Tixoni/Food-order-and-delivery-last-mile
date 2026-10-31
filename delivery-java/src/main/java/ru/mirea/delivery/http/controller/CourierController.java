package ru.mirea.delivery.http.controller;

import ru.mirea.delivery.app.CourierService;
import ru.mirea.delivery.error.ValidationException;
import ru.mirea.delivery.http.Access;
import ru.mirea.delivery.http.Controller;
import ru.mirea.delivery.http.Router;

import java.util.List;

/** Приложение курьера: смена, позиция, предложения, отметки (DLV-1, 2, 4, 5). */
public final class CourierController implements Controller {
    private static final List<String> COURIER = List.of("courier");

    private final CourierService couriers;

    public CourierController(CourierService couriers) {
        this.couriers = couriers;
    }

    @Override
    public void register(Router router) {
        router.get("/couriers/me", Access.USER, COURIER, request -> couriers.dashboard(request.requireUser().id()));
        router.post("/couriers/me/shift", Access.USER, COURIER, request -> {
            if (!(request.bodyMap().get("onShift") instanceof Boolean onShift)) {
                throw new ValidationException("onShift: true или false");
            }
            return couriers.setShift(request.requireUser().id(), onShift);
        });
        router.post("/couriers/me/location", Access.USER, COURIER,
                request -> couriers.updateLocation(request.requireUser().id(), request.body()), 202);
        router.post("/offers/{offerId}/accept", Access.USER, COURIER,
                request -> couriers.accept(request.requireUser().id(), request.param("offerId")));
        router.post("/offers/{offerId}/reject", Access.USER, COURIER,
                request -> couriers.reject(request.requireUser().id(), request.param("offerId")));
        router.post("/deliveries/{deliveryId}/picked-up", Access.USER, COURIER,
                request -> couriers.pickedUp(request.requireUser().id(), request.param("deliveryId")));
        router.post("/deliveries/{deliveryId}/delivered", Access.USER, COURIER,
                request -> couriers.delivered(request.requireUser().id(), request.param("deliveryId")));
    }
}
