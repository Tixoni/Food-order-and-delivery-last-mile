/**
 * Планирование «последней мили»: заказы с временными окнами → распределение
 * по курьерам → маршрут каждого курьера → проверка окон.
 *
 * <ul>
 *   <li>{@link ru.mirea.delivery.lastmile.Graph}, {@link ru.mirea.delivery.lastmile.Dijkstra},
 *       {@link ru.mirea.delivery.lastmile.DistanceMatrix} — граф улиц и кратчайшие пути;</li>
 *   <li>{@link ru.mirea.delivery.lastmile.RouteEvaluator} — проигрывание маршрута во времени;</li>
 *   <li>{@link ru.mirea.delivery.lastmile.RoutingStrategy} — построение маршрута:
 *       {@link ru.mirea.delivery.lastmile.GreedyStrategy} (ближайший сосед) и
 *       {@link ru.mirea.delivery.lastmile.TwoOptStrategy} (улучшение 2-opt поверх другой стратегии);</li>
 *   <li>{@link ru.mirea.delivery.lastmile.AssignmentPolicy} — распределение заказов по курьерам;</li>
 *   <li>{@link ru.mirea.delivery.lastmile.Planner} — весь расчёт, {@link ru.mirea.delivery.lastmile.PlanReport} — отчёт.</li>
 * </ul>
 *
 * Время — минуты от начала планирования, расстояние — метры. Результаты
 * совпадают с процедурной Python-версией ({@code delivery-py/delivery/lastmile})
 * на тех же входных данных.
 */
package ru.mirea.delivery.lastmile;
