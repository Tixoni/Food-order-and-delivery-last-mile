"""
Отправка событий в брокер — вторая половина Transactional Outbox (REL-3).

Запрос завершается, как только событие записано в `outbox_messages` той
же транзакцией, что и изменение данных. Этот фоновый проход забирает
неотправленные строки и публикует их в топик `delivery.events`. Брокер
недоступен — события копятся в таблице и уходят после восстановления.

Гарантия — «хотя бы один раз»; от дублей защищается потребитель по
`messageId` (REL-4). Выборка с `FOR UPDATE SKIP LOCKED`: несколько
экземпляров сервиса не возьмут одно сообщение дважды.
"""

import json
import time

from confluent_kafka import KafkaException, Producer
from confluent_kafka.admin import AdminClient, NewTopic

from . import db, log
from .events import TOPIC

PARTITIONS = 3
TOPIC_RETRY_SECONDS = 5
SEND_TIMEOUT_SECONDS = 10

_state = {"producer": None, "topic_ready": False, "next_topic_attempt": 0.0}


def _servers(kafka_config):
    return ",".join(kafka_config["brokers"])


def ensure_topic(kafka_config):
    """Топик создаётся явно: брокер настроен не создавать топики сам."""
    if _state["topic_ready"] or time.monotonic() < _state["next_topic_attempt"]:
        return
    _state["next_topic_attempt"] = time.monotonic() + TOPIC_RETRY_SECONDS
    admin = AdminClient({"bootstrap.servers": _servers(kafka_config), "log_level": 0})
    try:
        futures = admin.create_topics(
            [NewTopic(TOPIC, num_partitions=PARTITIONS, replication_factor=1)], request_timeout=5
        )
        futures[TOPIC].result(timeout=10)
    except KafkaException as error:
        if "TOPIC_ALREADY_EXISTS" not in str(error):
            log.warn("топик событий не подготовлен", component="outbox", topic=TOPIC, error=str(error))
            return
    except Exception as error:  # noqa: BLE001 — брокер недоступен: повтор позже
        log.warn("топик событий не подготовлен", component="outbox", topic=TOPIC, error=str(error))
        return
    _state["topic_ready"] = True


def _producer(kafka_config):
    if _state["producer"] is None:
        _state["producer"] = Producer(
            {
                "bootstrap.servers": _servers(kafka_config),
                "client.id": kafka_config["client_id"],
                # Идемпотентный режим: брокер отбрасывает дубль после потери
                # подтверждения; одно неподтверждённое сообщение — порядок сохраняется.
                "enable.idempotence": True,
                "max.in.flight.requests.per.connection": 1,
                "message.timeout.ms": SEND_TIMEOUT_SECONDS * 1000,
                "log_level": 0,
            }
        )
    return _state["producer"]


def _drop_producer():
    _state["producer"] = None


def publish_batch(kafka_config, batch_size):
    """Один проход: взять неотправленные, отправить, отметить. Возвращает число отправленных."""
    with db.transaction() as conn:
        rows = db.fetch_all(
            conn,
            """SELECT id, aggregate_id, event_type, payload, headers
                 FROM outbox_messages
                WHERE published_at IS NULL
                ORDER BY id
                LIMIT %s
                FOR UPDATE SKIP LOCKED""",
            (batch_size,),
        )
        if not rows:
            return 0
        ids = [row["id"] for row in rows]
        failures = []

        def remember_failure(error, message):
            if error is not None:
                failures.append(str(error))

        try:
            producer = _producer(kafka_config)
            for row in rows:
                headers = row["headers"] or {}
                producer.produce(
                    TOPIC,
                    # Ключ партиции задаёт порядок: события одного заказа — в одну партицию.
                    key=str(headers.get("partitionKey") or row["aggregate_id"]),
                    value=json.dumps(row["payload"], ensure_ascii=False, separators=(",", ":")),
                    headers=[(name, str(value)) for name, value in headers.items()],
                    on_delivery=remember_failure,
                )
            left = producer.flush(SEND_TIMEOUT_SECONDS)
            if left or failures:
                raise RuntimeError(failures[0] if failures else f"не подтверждено сообщений: {left}")
        except Exception as error:  # noqa: BLE001 — отметка о попытке, повтор позже
            # Число попыток и последняя ошибка — в таблице: видно, что застряло и почему.
            db.execute(
                conn,
                """UPDATE outbox_messages SET attempts = attempts + 1, last_error = %s
                    WHERE id = ANY(%s::bigint[])""",
                (str(error)[:1000], ids),
            )
            _drop_producer()
            log.warn("не удалось отправить события в брокер", component="outbox", error=str(error))
            return 0

        db.execute(
            conn, "UPDATE outbox_messages SET published_at = now() WHERE id = ANY(%s::bigint[])", (ids,)
        )
        log.debug(
            "события отправлены",
            component="outbox",
            count=len(rows),
            types=sorted({row["event_type"] for row in rows}),
        )
        return len(rows)


def tick(config):
    ensure_topic(config["kafka"])
    if not _state["topic_ready"]:
        return 0
    return publish_batch(config["kafka"], config["outbox"]["batch_size"])
