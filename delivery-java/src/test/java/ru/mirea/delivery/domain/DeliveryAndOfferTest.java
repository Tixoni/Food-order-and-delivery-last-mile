package ru.mirea.delivery.domain;

import org.junit.jupiter.api.Test;
import ru.mirea.delivery.error.ConflictException;
import ru.mirea.delivery.error.DomainRuleException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryAndOfferTest {
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    private static Delivery open() {
        return Delivery.open("d1", "o1", null, null, null, new GeoPoint(55.75, 37.61), new GeoPoint(55.76, 37.62),
                null, null, null, "k1", "corr", NOW);
    }

    @Test
    void deliveredOnlyAfterPickedUp() {
        Delivery delivery = open();
        delivery.accept("c1", NOW);
        assertThrows(DomainRuleException.class, () -> delivery.complete("c1", NOW));
        assertTrue(delivery.pickUp("c1", NOW));
        assertFalse(delivery.pickUp("c1", NOW));
        assertTrue(delivery.complete("c1", NOW));
        assertEquals(DeliveryStatus.DELIVERED, delivery.status());
        assertFalse(delivery.cancel(NOW));
    }

    @Test
    void foreignCourierCannotMarkDelivery() {
        Delivery delivery = open();
        delivery.accept("c1", NOW);
        assertThrows(ConflictException.class, () -> delivery.pickUp("c2", NOW));
    }

    @Test
    void secondAcceptIsConflict() {
        Delivery delivery = open();
        delivery.accept("c1", NOW);
        assertThrows(ConflictException.class, () -> delivery.accept("c2", NOW));
    }

    @Test
    void expiredOfferCannotBeAccepted() {
        Offer offer = Offer.make("a1", "d1", "c1", 1, NOW, 90);
        assertThrows(ConflictException.class,
                () -> offer.respond("c1", OfferResponse.ACCEPTED, NOW.plusSeconds(90)));
        assertTrue(offer.isOpen());
    }

    @Test
    void offerForAnotherCourierIsConflict() {
        Offer offer = Offer.make("a1", "d1", "c1", 1, NOW, 90);
        assertThrows(ConflictException.class, () -> offer.respond("c2", OfferResponse.ACCEPTED, NOW));
        offer.respond("c1", OfferResponse.ACCEPTED, NOW.plusSeconds(10));
        assertThrows(ConflictException.class, () -> offer.respond("c1", OfferResponse.REJECTED, NOW));
        offer.expire(NOW);
        assertEquals(OfferResponse.ACCEPTED, offer.response());
    }

    @Test
    void invalidCoordinatesAreRuleViolation() {
        assertThrows(DomainRuleException.class, () -> GeoPoint.of(91, 37));
        assertThrows(DomainRuleException.class, () -> GeoPoint.of("55", 37));
    }
}
