package ru.mirea.delivery.domain;

/** Пеший курьер: 5 км/ч, не больше двух заказов на руках. */
public final class FootCourier extends Courier {
    static final VehicleProfile PROFILE = new VehicleProfile(5, 2);

    FootCourier(CourierRecord record, VehicleProfile profile) {
        super(record, profile);
    }

    @Override
    public VehicleType vehicleType() {
        return VehicleType.FOOT;
    }

    @Override
    protected VehicleProfile defaultProfile() {
        return PROFILE;
    }
}
