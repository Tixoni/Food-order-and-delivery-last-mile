"""
Журнал — JSON-строки в стандартный вывод, как у остальных сервисов:
Alloy собирает их в Loki, поля совпадают с TS-версией (OBS-1).
Телефон, адрес, токены и пароли маскируются.
"""

import json
import sys
import threading

from . import clock
from .context import current_correlation_id

LEVELS = {"debug": 10, "info": 20, "warn": 30, "error": 40}

SENSITIVE_KEYS = (
    "password", "passwd", "secret", "token", "accesstoken", "refreshtoken",
    "authorization", "apikey", "cardnumber", "pan", "cvv", "cvc", "expiry",
    "phone", "email", "address",
)
MASK = "***"
MAX_DEPTH = 6

_settings = {"service": "delivery", "level": LEVELS["info"]}
_lock = threading.Lock()


def configure(service, level):
    _settings["service"] = service
    _settings["level"] = LEVELS[level]


def is_sensitive(key):
    normalized = key.lower().replace("_", "").replace("-", "")
    return any(word in normalized for word in SENSITIVE_KEYS)


def mask(value, depth=0):
    if depth > MAX_DEPTH:
        return "[глубина превышена]"
    if isinstance(value, dict):
        return {
            key: MASK if is_sensitive(str(key)) else mask(item, depth + 1)
            for key, item in value.items()
        }
    if isinstance(value, (list, tuple)):
        return [mask(item, depth + 1) for item in value]
    return value


def log(level, message, **fields):
    if LEVELS[level] < _settings["level"]:
        return
    record = {
        "timestamp": clock.iso(clock.utcnow()),
        "level": level,
        "service": _settings["service"],
        "message": message,
    }
    correlation_id = current_correlation_id()
    if correlation_id:
        record["correlationId"] = correlation_id
    record.update(mask(fields))
    line = json.dumps(record, ensure_ascii=False, default=str)
    with _lock:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()


def debug(message, **fields):
    log("debug", message, **fields)


def info(message, **fields):
    log("info", message, **fields)


def warn(message, **fields):
    log("warn", message, **fields)


def error(message, **fields):
    log("error", message, **fields)
