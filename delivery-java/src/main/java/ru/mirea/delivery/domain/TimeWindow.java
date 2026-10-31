package ru.mirea.delivery.domain;

/**
 * Окно доставки, которое выбрал клиент: с {@code start} по {@code end}
 * (минуты от начала планирования). Раньше начала — курьер ждёт у двери,
 * позже конца — опоздание.
 */
public record TimeWindow(double start, double end) {
    private static final double EPS = 1e-9;

    public TimeWindow {
        if (start > end) {
            throw new IllegalArgumentException("окно доставки задано неверно");
        }
    }

    /** Когда начнётся вручение при приезде в {@code arrival}: не раньше начала окна. */
    public double serviceStart(double arrival) {
        return Math.max(arrival, start);
    }

    /** Насколько вручение в {@code moment} позже окна; 0 — успели. */
    public double lateness(double moment) {
        return moment > end + EPS ? moment - end : 0;
    }
}
