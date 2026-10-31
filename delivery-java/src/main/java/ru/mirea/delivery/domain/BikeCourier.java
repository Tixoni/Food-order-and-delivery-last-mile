package ru.mirea.delivery.domain;

/** Курьер на велосипеде: 14 км/ч, до трёх заказов. */
public final class BikeCourier extends Courier {
    static final VehicleProfile PROFILE = new VehicleProfile(14, 3);

    BikeCourier(CourierRecord record, VehicleProfile profile) {
        super(record, profile);
    }

    @Override
    public VehicleType vehicleType() {
        return VehicleType.BIKE;
    }

    @Override
    protected VehicleProfile defaultProfile() {
        return PROFILE;
    }
}
