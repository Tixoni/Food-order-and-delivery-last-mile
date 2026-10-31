package ru.mirea.delivery.domain;

/**
 * Скорость и вместимость транспорта. У каждого вида курьера — свой профиль
 * по умолчанию; планировщик может подставить профиль из условия задачи.
 *
 * @param speedKmh средняя скорость в городе с остановками и пробками, км/ч
 * @param capacity сколько единиц заказов курьер везёт одновременно
 */
public record VehicleProfile(double speedKmh, double capacity) {
    public VehicleProfile {
        if (speedKmh <= 0 || capacity <= 0) {
            throw new IllegalArgumentException("скорость и вместимость должны быть больше нуля");
        }
    }
}
