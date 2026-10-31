package ru.mirea.delivery.domain;

/** Транспорт курьера; в базе и API — строчными буквами. */
public enum VehicleType {
    FOOT, BIKE, CAR;

    public String wire() {
        return name().toLowerCase();
    }

    /** Тип по строке из базы или запроса; {@code null} — такого транспорта нет. */
    public static VehicleType fromWire(Object value) {
        if (value instanceof String text) {
            for (VehicleType type : values()) {
                if (type.wire().equals(text)) {
                    return type;
                }
            }
        }
        return null;
    }
}
