package ru.mirea.delivery.lastmile;

import java.util.List;

/**
 * Способ построить маршрут курьера по набору заказов. Стратегии
 * взаимозаменяемы: распределение заказов и планировщик работают с любой,
 * а 2-opt оборачивает другую стратегию и улучшает её результат.
 */
public interface RoutingStrategy {
    Route build(FleetCourier courier, List<String> orderIds);
}
