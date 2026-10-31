package ru.mirea.delivery.lastmile;

/** Посещение остановки: приезд, начало обслуживания (после ожидания) и отъезд, минуты. */
public record Visit(String order, StopKind kind, String node, double arrival, double start, double departure) {
}
