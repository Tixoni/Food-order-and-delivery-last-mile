package ru.mirea.delivery.lastmile;

import ru.mirea.delivery.domain.Courier;

/**
 * Курьер в задаче планирования: объект предметной области (вид транспорта
 * определяет скорость и вместимость) плюс точка выхода и длина смены.
 * Время в пути считает сам курьер — планировщик не ветвится по транспорту.
 */
public record FleetCourier(String id, String vehicle, Courier courier, String node, double shiftMinutes) {
    public double travelMinutes(double meters) {
        return courier.travelMinutes(meters);
    }

    public double capacity() {
        return courier.capacity();
    }
}
