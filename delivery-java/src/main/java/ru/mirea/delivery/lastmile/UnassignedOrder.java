package ru.mirea.delivery.lastmile;

/** Заказ без курьера и причина: его отдают оператору или переносят в следующий раунд. */
public record UnassignedOrder(String order, String reason) {
}
