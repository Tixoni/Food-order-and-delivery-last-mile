package ru.mirea.delivery.domain;

import org.junit.jupiter.api.Test;
import ru.mirea.delivery.error.DomainRuleException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CourierTest {
    private static Courier courier(VehicleType type, String userId) {
        return Courier.register("c1", "Курьер", type, userId, null);
    }

    @Test
    void vehicleDefinesSubclassSpeedAndCapacity() {
        assertInstanceOf(FootCourier.class, courier(VehicleType.FOOT, "u"));
        assertInstanceOf(BikeCourier.class, courier(VehicleType.BIKE, "u"));
        Courier car = courier(VehicleType.CAR, "u");
        assertInstanceOf(CarCourier.class, car);
        assertEquals(20, car.speedKmh());
        assertEquals(6, car.capacity());
        // 1 км: пешком 12 мин, на велосипеде быстрее
        assertEquals(12, courier(VehicleType.FOOT, "u").travelMinutes(1000), 1e-9);
        assertTrue(courier(VehicleType.BIKE, "u").travelSeconds(1000) < courier(VehicleType.FOOT, "u").travelSeconds(1000));
    }

    @Test
    void shiftRequiresLinkedAccount() {
        assertThrows(DomainRuleException.class, () -> courier(VehicleType.BIKE, null).startShift());
    }

    @Test
    void repeatedShiftStartChangesNothing() {
        Courier courier = courier(VehicleType.BIKE, "u");
        assertTrue(courier.startShift());
        assertFalse(courier.startShift());
        assertEquals(CourierStatus.AVAILABLE, courier.status());
    }

    @Test
    void cannotLeaveShiftWithDelivery() {
        Courier courier = courier(VehicleType.BIKE, "u");
        courier.startShift();
        courier.offer();
        courier.accept("d1");
        assertThrows(DomainRuleException.class, courier::endShift);
        courier.free();
        assertTrue(courier.endShift());
        assertEquals(CourierStatus.OFFLINE, courier.status());
    }

    @Test
    void acceptOnlyOfferedOrder() {
        Courier courier = courier(VehicleType.FOOT, "u");
        courier.startShift();
        assertThrows(DomainRuleException.class, () -> courier.accept("d1"));
        courier.offer();
        courier.declineOffer();
        assertEquals(CourierStatus.AVAILABLE, courier.status());
    }
}
