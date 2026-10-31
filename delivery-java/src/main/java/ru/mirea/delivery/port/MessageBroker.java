package ru.mirea.delivery.port;

import java.util.List;
import java.util.Map;

/**
 * Брокер сообщений для отправки событий из outbox. Сценарии с ним не
 * работают напрямую — только фоновая отправка (вторая половина
 * Transactional Outbox).
 */
public interface MessageBroker extends AutoCloseable {

    /** Сообщение: ключ партиции, тело JSON и заголовки. */
    record Message(String key, String value, Map<String, String> headers) {
    }

    /** Топик создаётся явно: брокер настроен не создавать топики сам. */
    void ensureTopic(String topic, int partitions);

    /** Отправить и дождаться подтверждения всех сообщений; иначе — исключение. */
    void send(String topic, List<Message> messages);

    /** Сбросить соединение после отказа — следующая отправка подключится заново. */
    void reset();

    @Override
    void close();
}
