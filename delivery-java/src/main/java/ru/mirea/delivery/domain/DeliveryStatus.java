package ru.mirea.delivery.domain;

/** Статус доставки; в базе и API — как есть, заглавными. */
public enum DeliveryStatus {
    PENDING, ASSIGNED, PICKED_UP, DELIVERED, CANCELLED;

    public boolean isFinal() {
        return this == DELIVERED || this == CANCELLED;
    }
}
