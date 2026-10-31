"""Представления для ответов API — те же поля, что у TS-версии (camelCase)."""


def courier_view(courier):
    return {
        "id": courier["id"],
        "fullName": courier["full_name"],
        "phone": courier["phone"],
        "vehicleType": courier["vehicle_type"],
        "status": courier["status"],
        "isActive": courier["is_active"],
        "userId": courier["user_id"],
    }


def delivery_view(delivery, with_contact=True, route=None):
    return {
        "id": delivery["id"],
        "orderId": delivery["order_id"],
        "orderNumber": delivery["order_number"],
        "restaurantId": delivery["restaurant_id"],
        "dropoffAddress": delivery["dropoff_address"],
        # До принятия — без телефона клиента (SEC-4).
        "contactPhone": delivery["contact_phone"] if with_contact else None,
        "status": delivery["status"],
        "courierId": delivery["courier_id"],
        "pickup": delivery["pickup"],
        "dropoff": delivery["dropoff"],
        "readyAt": delivery["ready_at"],
        "acceptedAt": delivery["accepted_at"],
        "pickedUpAt": delivery["picked_up_at"],
        "deliveredAt": delivery["delivered_at"],
        "route": route,
    }
