package ru.mirea.delivery.port;

/** История координат: пишется пачками — транзакция на каждую координату положила бы базу. */
public interface LocationHistory {
    void add(String courierId, Position position);
}
