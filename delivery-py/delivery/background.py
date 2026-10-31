"""
Фоновые процессы: отправка событий (outbox), таймер предложений (DLV-4)
и пакетная запись истории координат. Каждый — поток, повторяющий одну
функцию через заданный интервал; сбой прохода записывается в журнал
и не останавливает поток.
"""

import threading

from . import dispatch, history, log, outbox


def _repeat(name, interval_seconds, work, stop):
    def loop():
        while not stop.wait(interval_seconds):
            try:
                work()
            except Exception as error:  # noqa: BLE001 — следующий проход повторит
                log.warn("фоновый проход не выполнен", component=name, error=str(error))

    thread = threading.Thread(target=loop, name=name, daemon=True)
    thread.start()
    return thread


def _expire_offers():
    """Срок отклика хранится в строке предложения: перезапуск сервиса таймер не теряет (REL-6)."""
    expired = dispatch.expire_offers()
    if expired:
        log.info(
            "предложения истекли, заказ предложен следующим",
            component="offer-timeouts",
            expired=expired,
        )


def start(config):
    """Запустить фоновые потоки. Возвращает событие остановки."""
    stop = threading.Event()
    _repeat("outbox", config["outbox"]["poll_interval_ms"] / 1000, lambda: outbox.tick(config), stop)
    _repeat("offer-timeouts", config["dispatch"]["sweep_interval_ms"] / 1000, _expire_offers, stop)
    _repeat("location-history", config["locations"]["flush_interval_ms"] / 1000, history.flush, stop)
    return stop
