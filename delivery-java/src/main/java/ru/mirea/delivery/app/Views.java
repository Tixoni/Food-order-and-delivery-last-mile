package ru.mirea.delivery.app;

import ru.mirea.delivery.common.Maps;
import ru.mirea.delivery.domain.Courier;
import ru.mirea.delivery.domain.Delivery;
import ru.mirea.delivery.domain.DeliveryRecord;
import ru.mirea.delivery.port.Position;
import ru.mirea.delivery.port.RoadRoute;

import java.util.Map;

/** Представления для ответов API — те же поля и порядок, что у TS-версии. */
public final class Views {
    private Views() {
    }

    public static Map<String, Object> courier(Courier courier) {
        return Maps.of(
                "id", courier.id(),
                "fullName", courier.fullName(),
                "phone", courier.phone(),
                "vehicleType", courier.vehicleType().wire(),
                "status", courier.status().wire(),
                "isActive", courier.active(),
                "userId", courier.userId());
    }

    public static Map<String, Object> delivery(Delivery delivery) {
        return delivery(delivery, true, null);
    }

    public static Map<String, Object> delivery(Delivery delivery, boolean withContact, RoadRoute route) {
        DeliveryRecord d = delivery.snapshot();
        return Maps.of(
                "id", d.id(),
                "orderId", d.orderId(),
                "orderNumber", d.orderNumber(),
                "restaurantId", d.restaurantId(),
                "dropoffAddress", d.dropoffAddress(),
                // До принятия — без телефона клиента (SEC-4).
                "contactPhone", withContact ? d.contactPhone() : null,
                "status", d.status().name(),
                "courierId", d.courierId(),
                "pickup", d.pickup(),
                "dropoff", d.dropoff(),
                "readyAt", d.readyAt(),
                "acceptedAt", d.acceptedAt(),
                "pickedUpAt", d.pickedUpAt(),
                "deliveredAt", d.deliveredAt(),
                "route", route);
    }

    /** Позиция из кэша — с точностью; последняя известная из базы — без неё. */
    public static Map<String, Object> position(Position position, boolean withAccuracy) {
        if (position == null) {
            return null;
        }
        Map<String, Object> view = Maps.of(
                "latitude", position.latitude(),
                "longitude", position.longitude(),
                "at", position.at());
        if (withAccuracy) {
            view.put("accuracy", position.accuracy());
        }
        return view;
    }
}
