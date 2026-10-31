package ru.mirea.delivery.domain;

/** Статус курьера; в базе и API — строчными буквами. */
public enum CourierStatus {
    OFFLINE, AVAILABLE, ASSIGNED, BUSY;

    public String wire() {
        return name().toLowerCase();
    }

    public static CourierStatus fromWire(String value) {
        return CourierStatus.valueOf(value.toUpperCase());
    }
}
