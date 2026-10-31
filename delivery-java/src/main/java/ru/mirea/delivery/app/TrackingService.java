package ru.mirea.delivery.app;

import ru.mirea.delivery.common.Maps;
import ru.mirea.delivery.domain.Courier;
import ru.mirea.delivery.domain.Delivery;
import ru.mirea.delivery.domain.DeliveryStatus;
import ru.mirea.delivery.domain.VehicleType;
import ru.mirea.delivery.error.NotFoundException;
import ru.mirea.delivery.error.ValidationException;
import ru.mirea.delivery.port.DeliveryRepository;
import ru.mirea.delivery.port.PositionCache;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Позиция курьера для клиента (TRK-2) и учёт курьеров администратором (ADM-2).
 *
 * SEC-7: позицию видит только клиент этого заказа и оператор. Чужому
 * клиенту — «не найдено», а не «нет прав»: не подтверждаем, что такой
 * заказ существует.
 */
public final class TrackingService {
    private static final List<String> STAFF = List.of("operator", "admin");

    private final DeliveryRepository deliveries;
    private final PositionCache positions;
    private final Routing routing;

    public TrackingService(DeliveryRepository deliveries, PositionCache positions, Routing routing) {
        this.deliveries = deliveries;
        this.positions = positions;
        this.routing = routing;
    }

    public Map<String, Object> courierPosition(String orderId, String userId, List<String> roles) {
        if (!Ids.isUuid(orderId)) {
            throw new NotFoundException("доставка не найдена");
        }
        Delivery delivery = deliveries.deliveryOfOrder(orderId);
        boolean staff = roles.stream().anyMatch(STAFF::contains);
        if (delivery == null || (!staff && !userId.equals(delivery.customerId()))) {
            throw new NotFoundException("доставка не найдена", Maps.of("orderId", orderId));
        }
        // Позиция показывается, пока курьер едет к ресторану или к клиенту.
        boolean visible = delivery.courierId() != null && (delivery.status() == DeliveryStatus.ASSIGNED
                || delivery.status() == DeliveryStatus.PICKED_UP);
        return Maps.of(
                "orderId", orderId,
                "deliveryId", delivery.id(),
                "status", delivery.status().name(),
                "courierId", visible ? delivery.courierId() : null,
                // Нет свежей позиции — клиент увидит «позиция устарела», а не точку часовой давности.
                "position", visible ? Views.position(positions.get(delivery.courierId()), true) : null,
                "dropoff", delivery.dropoff(),
                "pickup", delivery.pickup(),
                "route", routing.deliveryRoute(delivery));
    }

    public List<Map<String, Object>> listCouriers() {
        return deliveries.listCouriers().stream().map(Views::courier).toList();
    }

    /** ADM-2: курьер заводится администратором и привязывается к учётной записи. */
    public Map<String, Object> registerCourier(Object body) {
        Map<?, ?> map = body instanceof Map<?, ?> value ? value : Map.of();
        String fullName = map.get("fullName") instanceof String text ? text.trim() : "";
        if (fullName.isEmpty() || fullName.length() > 200) {
            throw new ValidationException("укажите имя курьера (до 200 символов)");
        }
        Object vehicle = map.get("vehicleType");
        VehicleType type = vehicle == null || "".equals(vehicle) ? VehicleType.FOOT : VehicleType.fromWire(vehicle);
        if (type == null) {
            throw new ValidationException("vehicleType: одно из " + Arrays.stream(VehicleType.values())
                    .map(VehicleType::wire).collect(Collectors.joining(", ")));
        }
        Object userId = map.get("userId");
        if (userId != null && !"".equals(userId) && !Ids.isUuid(userId)) {
            throw new ValidationException("userId: идентификатор учётной записи Keycloak");
        }
        String phone = map.get("phone") instanceof String text && !text.isBlank() ? text.trim() : null;
        Courier courier = Courier.register(UUID.randomUUID().toString(), fullName, type,
                userId == null || "".equals(userId) ? null : (String) userId, phone);
        deliveries.inTransaction(tx -> {
            tx.insertCourier(courier);
            return null;
        });
        return Views.courier(courier);
    }
}
