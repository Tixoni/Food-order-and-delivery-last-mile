"""
Сквозной идентификатор действия (OBS-1).

Каждый запрос обрабатывается в своём потоке, поэтому идентификатор
хранится в `contextvars`: он доступен в любой функции обработки и
попадает в журнал и в события, не передаваясь параметром.
"""

import re
import uuid
from contextlib import contextmanager
from contextvars import ContextVar

CORRELATION_HEADER = "x-correlation-id"

# Тот же формат, что принимает TS-версия: идентификатор пишется
# в колонку VARCHAR(100) и в заголовки сообщений брокера.
ACCEPTED_ID = re.compile(r"[A-Za-z0-9._:-]{1,100}")

_correlation_id = ContextVar("correlation_id", default=None)


def new_correlation_id():
    return str(uuid.uuid4())


def accept_correlation_id(incoming):
    """Идентификатор от вызывающего — если он безопасного вида, иначе новый."""
    if isinstance(incoming, str) and ACCEPTED_ID.fullmatch(incoming):
        return incoming
    return new_correlation_id()


def current_correlation_id():
    """Идентификатор текущего действия или `None` вне действия."""
    return _correlation_id.get()


def get_correlation_id():
    """Идентификатор текущего действия; вне действия — новый, чтобы запись не осталась без привязки."""
    return _correlation_id.get() or new_correlation_id()


@contextmanager
def correlation(correlation_id):
    token = _correlation_id.set(correlation_id)
    try:
        yield correlation_id
    finally:
        _correlation_id.reset(token)
