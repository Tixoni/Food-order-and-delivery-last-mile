package ru.mirea.delivery.http.controller;

import ru.mirea.delivery.app.DispatchService;
import ru.mirea.delivery.http.Access;
import ru.mirea.delivery.http.Controller;
import ru.mirea.delivery.http.Router;

import java.util.List;

/** Внутренний API для оркестратора саги — со служебным токеном, без пользователя. */
public final class InternalDeliveryController implements Controller {
    private final DispatchService dispatch;

    public InternalDeliveryController(DispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @Override
    public void register(Router router) {
        router.post("/deliveries", Access.INTERNAL, List.of(), request -> dispatch.requestCourier(request.body()));
        router.post("/deliveries/release", Access.INTERNAL, List.of(),
                request -> dispatch.releaseCourier(request.body()));
    }
}
