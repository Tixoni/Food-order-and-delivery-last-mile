package ru.mirea.delivery.domain;

import ru.mirea.delivery.common.Maps;
import ru.mirea.delivery.error.ConflictException;

import java.time.Duration;
import java.time.Instant;

/**
 * Предложение заказа курьеру с номером попытки и сроком отклика (DLV-4).
 * Каждое предложение — отдельная запись, поэтому цепочка переназначений
 * видна целиком.
 */
public final class Offer {
    private final String id;
    private final String deliveryId;
    private final String courierId;
    private final int sequence;
    private final Instant offeredAt;
    private final Instant expiresAt;
    private Instant respondedAt;
    private OfferResponse response;

    private Offer(OfferRecord r) {
        id = r.id();
        deliveryId = r.deliveryId();
        courierId = r.courierId();
        sequence = r.sequence();
        offeredAt = r.offeredAt();
        expiresAt = r.expiresAt();
        respondedAt = r.respondedAt();
        response = r.response();
    }

    public static Offer restore(OfferRecord record) {
        return new Offer(record);
    }

    public static Offer make(String id, String deliveryId, String courierId, int sequence, Instant now,
                             int ttlSeconds) {
        return new Offer(new OfferRecord(id, deliveryId, courierId, sequence, now,
                now.plus(Duration.ofSeconds(ttlSeconds)), null, null));
    }

    public boolean isOpen() {
        return response == null;
    }

    /**
     * Ответ курьера. Принять просроченное нельзя: к этому времени заказ
     * уже мог уйти следующему курьеру.
     */
    public void respond(String byCourier, OfferResponse answer, Instant at) {
        if (!courierId.equals(byCourier)) {
            throw new ConflictException("предложение адресовано другому курьеру");
        }
        if (!isOpen()) {
            throw new ConflictException("предложение уже закрыто", Maps.of("response", response.name()));
        }
        if (answer == OfferResponse.ACCEPTED && !at.isBefore(expiresAt)) {
            throw new ConflictException("предложение просрочено: заказ предложен другому курьеру");
        }
        close(answer, at);
    }

    public void expire(Instant at) {
        if (isOpen()) {
            close(OfferResponse.TIMEOUT, at);
        }
    }

    public void cancel(Instant at) {
        if (isOpen()) {
            close(OfferResponse.CANCELLED, at);
        }
    }

    private void close(OfferResponse answer, Instant at) {
        response = answer;
        respondedAt = at;
    }

    public OfferRecord snapshot() {
        return new OfferRecord(id, deliveryId, courierId, sequence, offeredAt, expiresAt, respondedAt, response);
    }

    public String id() {
        return id;
    }

    public String deliveryId() {
        return deliveryId;
    }

    public String courierId() {
        return courierId;
    }

    public int sequence() {
        return sequence;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public OfferResponse response() {
        return response;
    }
}
