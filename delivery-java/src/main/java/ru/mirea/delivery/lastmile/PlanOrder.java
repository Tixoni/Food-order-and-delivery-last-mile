package ru.mirea.delivery.lastmile;

import ru.mirea.delivery.domain.TimeWindow;

/** Заказ в задаче: где забрать, куда отвезти, когда готов, окно клиента и размер. */
public record PlanOrder(String id, String pickupNode, String dropoffNode, double ready, TimeWindow window,
                        double size) {
}
