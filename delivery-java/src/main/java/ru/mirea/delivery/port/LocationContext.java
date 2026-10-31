package ru.mirea.delivery.port;

import ru.mirea.delivery.domain.CourierStatus;

/** Кому пришла координата: курьер, его статус и заказ, который он везёт (если везёт). */
public record LocationContext(String courierId, CourierStatus status, String orderId) {
}
