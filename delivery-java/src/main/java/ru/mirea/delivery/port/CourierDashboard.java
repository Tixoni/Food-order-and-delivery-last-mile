package ru.mirea.delivery.port;

import ru.mirea.delivery.domain.Courier;
import ru.mirea.delivery.domain.Delivery;
import ru.mirea.delivery.domain.Offer;

/** Экран курьера: сам курьер, открытое предложение (с доставкой) и текущая доставка. */
public record CourierDashboard(Courier courier, Offer offer, Delivery offerDelivery, Delivery delivery) {
}
