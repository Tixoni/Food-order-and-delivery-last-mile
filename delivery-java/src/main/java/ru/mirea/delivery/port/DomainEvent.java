package ru.mirea.delivery.port;

import ru.mirea.delivery.common.Maps;

import java.util.Map;

/**
 * Конверт доменного события — общий для всех сервисов системы
 * ({@code packages/shared}): по {@code messageId} потребитель отсекает
 * повторы (REL-4), по {@code correlationId} связываются записи одного
 * действия (OBS-1).
 */
public record DomainEvent(
        String messageId,
        String eventType,
        int eventVersion,
        String aggregateType,
        String aggregateId,
        String occurredAt,
        String correlationId,
        Map<String, Object> payload) {

    /** Поля в том порядке, в каком их пишет TS-версия. */
    public Map<String, Object> toMap() {
        return Maps.of(
                "messageId", messageId,
                "eventType", eventType,
                "eventVersion", eventVersion,
                "aggregateType", aggregateType,
                "aggregateId", aggregateId,
                "occurredAt", occurredAt,
                "correlationId", correlationId,
                "payload", payload);
    }
}
