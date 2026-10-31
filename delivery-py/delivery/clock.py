"""Время: текущий момент и формат дат, совместимый с JavaScript (`Date.toJSON`)."""

from datetime import datetime, timezone


def utcnow():
    return datetime.now(timezone.utc)


def iso(moment):
    """2026-10-31T09:15:00.123Z — так даты отдаёт TS-версия, так их ждут шлюз и order."""
    if moment is None:
        return None
    moment = moment.astimezone(timezone.utc)
    return moment.strftime("%Y-%m-%dT%H:%M:%S.") + f"{moment.microsecond // 1000:03d}Z"


def parse_iso(text):
    """Дата из строки ISO 8601; `None` — строка не дата."""
    if not isinstance(text, str) or not text:
        return None
    try:
        moment = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None
    return moment if moment.tzinfo else moment.replace(tzinfo=timezone.utc)
