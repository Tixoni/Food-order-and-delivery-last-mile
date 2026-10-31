"""
Запуск сервиса: `python -m delivery`.

Порядок: настройки → журнал → база, Redis, маршрутизатор, вход →
фоновые потоки → HTTP-сервер. При остановке — фоновые потоки, последняя
пачка координат, соединения.
"""

import logging
import signal
import sys

from waitress import create_server

from . import auth, background, config, db, history, log, positions, routing
from .api import application


def _stop_on_signal(signum, frame):
    raise SystemExit(0)


def main():
    settings = config.apply(config.load_config())
    log.configure("delivery", settings["log_level"])
    logging.getLogger("waitress").setLevel(logging.WARNING)

    db.init_pool(settings["database"])
    positions.init_positions(settings["redis"], settings["locations"]["position_ttl_seconds"])
    history.init_history(settings["locations"]["max_buffer"])
    routing.init_routing(settings["routing"])
    auth.init_auth(settings["auth"])
    if settings["auth"]["disabled"]:
        log.warn("проверка токенов выключена (AUTH_DISABLED) — только для тестов")

    stop = background.start(settings)
    server = create_server(application, host="0.0.0.0", port=settings["port"], threads=16)
    signal.signal(signal.SIGTERM, _stop_on_signal)
    log.info(
        "сервис запущен",
        port=settings["port"],
        implementation="python",
        routing="osrm" if settings["routing"]["url"] else "по прямой",
    )
    try:
        server.run()
    except (KeyboardInterrupt, SystemExit):
        pass
    finally:
        stop.set()
        server.close()
        history.flush()
        positions.close_positions()
        db.close_pool()
        log.info("сервис остановлен")
    return 0


if __name__ == "__main__":
    sys.exit(main())
