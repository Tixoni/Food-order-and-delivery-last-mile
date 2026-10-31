package ru.mirea.delivery.lastmile;

/** Что курьер делает на остановке. */
public enum StopKind {
    PICKUP, DROPOFF;

    public String wire() {
        return name().toLowerCase();
    }
}
