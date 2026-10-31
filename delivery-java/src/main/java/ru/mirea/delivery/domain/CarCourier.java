package ru.mirea.delivery.domain;

/** Курьер на машине: 20 км/ч в городе с пробками, до шести заказов. */
public final class CarCourier extends Courier {
    static final VehicleProfile PROFILE = new VehicleProfile(20, 6);

    CarCourier(CourierRecord record, VehicleProfile profile) {
        super(record, profile);
    }

    @Override
    public VehicleType vehicleType() {
        return VehicleType.CAR;
    }

    @Override
    protected VehicleProfile defaultProfile() {
        return PROFILE;
    }
}
