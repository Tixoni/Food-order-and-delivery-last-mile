package ru.mirea.delivery.infra.routing;

import ru.mirea.delivery.common.Log;

/**
 * Размыкатель цепи (REL-2). Несколько отказов подряд размыкают цепь на
 * заданное время: вызовы в это время сразу получают отказ и не ждут
 * таймаута. По истечении срока пропускается одна пробная попытка: удача
 * замыкает цепь, отказ размыкает снова.
 */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final long openNanos;
    private final Log log;
    private State state = State.CLOSED;
    private int failures;
    private long openedUntil;

    public CircuitBreaker(String name, int failureThreshold, long openMillis) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openNanos = openMillis * 1_000_000;
        this.log = Log.of(name);
    }

    /** Можно ли сейчас обращаться к зависимости. */
    public synchronized boolean allows() {
        if (state == State.OPEN) {
            if (System.nanoTime() < openedUntil) {
                return false;
            }
            state = State.HALF_OPEN;
        }
        return true;
    }

    public synchronized void success() {
        if (state != State.CLOSED) {
            log.info(name + ": цепь closed");
        }
        state = State.CLOSED;
        failures = 0;
    }

    public synchronized void failure() {
        failures++;
        if (state == State.HALF_OPEN || failures >= failureThreshold) {
            state = State.OPEN;
            openedUntil = System.nanoTime() + openNanos;
            log.warn(name + ": цепь open");
        }
    }

    public synchronized State state() {
        return state;
    }
}
