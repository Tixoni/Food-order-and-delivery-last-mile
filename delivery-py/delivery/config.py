"""
Настройки из переменных окружения — те же имена, что у TS-версии,
поэтому сервис встаёт на место `delivery` в docker-compose без правок.
Проверка — при запуске: лучше не стартовать, чем работать с неверной настройкой.
"""

import os

DEV_INTERNAL_TOKEN = "delivery-internal-dev-token"

# Настройки процесса; заполняются в `apply` при запуске (или в тестах).
current = {}


def _required(env, name, fallback=None):
    value = env.get(name, fallback)
    if value is None or value == "":
        raise RuntimeError(f"не задана обязательная переменная окружения {name}")
    return value


def _numeric(env, name, fallback):
    raw = env.get(name)
    if raw is None or raw == "":
        return fallback
    try:
        value = int(raw)
    except ValueError:
        value = 0
    if value <= 0:
        raise RuntimeError(f"переменная окружения {name} должна быть целым положительным числом")
    return value


def load_config(env=None):
    env = os.environ if env is None else env
    level = env.get("LOG_LEVEL", "info")
    if level not in ("debug", "info", "warn", "error"):
        raise RuntimeError(f"недопустимое значение LOG_LEVEL: {level}")
    node_env = env.get("NODE_ENV", "development")
    auth_disabled = env.get("AUTH_DISABLED") == "true"
    if node_env == "production" and auth_disabled:
        raise RuntimeError("AUTH_DISABLED запрещён в production")
    internal_token = _required(env, "DELIVERY_INTERNAL_TOKEN", DEV_INTERNAL_TOKEN)
    if (
        node_env == "production"
        and internal_token == DEV_INTERNAL_TOKEN
        and env.get("ALLOW_DEV_SECRETS") != "true"
    ):
        raise RuntimeError(
            "в production нужен собственный DELIVERY_INTERNAL_TOKEN "
            "(для учебного стенда — ALLOW_DEV_SECRETS=true)"
        )

    osrm_url = env.get("OSRM_URL", "http://localhost:15000").strip()
    return {
        "port": _numeric(env, "DELIVERY_PORT", 3004),
        "node_env": node_env,
        "log_level": level,
        "database": {
            "host": _required(env, "POSTGRES_HOST", "localhost"),
            "port": _numeric(env, "POSTGRES_PORT", 15432),
            "user": _required(env, "POSTGRES_USER", "app"),
            "password": _required(env, "POSTGRES_PASSWORD", "app"),
            "dbname": _required(env, "DELIVERY_DB", "delivery_db"),
        },
        "redis": {
            "host": _required(env, "REDIS_HOST", "localhost"),
            "port": _numeric(env, "REDIS_PORT", 16379),
        },
        "kafka": {
            "brokers": [
                broker.strip()
                for broker in _required(env, "KAFKA_BROKERS", "localhost:19092").split(",")
                if broker.strip()
            ],
            "client_id": env.get("KAFKA_CLIENT_ID", "delivery"),
        },
        "outbox": {
            "poll_interval_ms": _numeric(env, "OUTBOX_POLL_INTERVAL_MS", 500),
            "batch_size": _numeric(env, "OUTBOX_BATCH_SIZE", 100),
        },
        "auth": {
            "issuer": _required(
                env, "KEYCLOAK_ISSUER", "http://localhost:18080/realms/food-delivery"
            ),
            "provider_url": env.get("KEYCLOAK_INTERNAL_URL") or None,
            "audience": _required(env, "AUTH_AUDIENCE", "food-delivery-api"),
            "disabled": auth_disabled,
        },
        "internal_token": internal_token,
        "dispatch": {
            "radius_meters": _numeric(env, "DISPATCH_RADIUS_METERS", 3000),
            "offer_ttl_seconds": _numeric(env, "OFFER_TTL_SECONDS", 90),
            "fresh_seconds": _numeric(env, "COURIER_FRESH_SECONDS", 120),
            "sweep_interval_ms": _numeric(env, "OFFER_SWEEP_INTERVAL_MS", 1000),
        },
        # Пустая строка — маршрутизатор выключен намеренно: всё по прямой (REL-2).
        "routing": {
            "url": osrm_url or None,
            "timeout_ms": _numeric(env, "OSRM_TIMEOUT_MS", 500),
        },
        "locations": {
            "position_ttl_seconds": _numeric(env, "POSITION_TTL_SECONDS", 60),
            "flush_interval_ms": _numeric(env, "LOCATION_FLUSH_INTERVAL_MS", 3000),
            "max_buffer": _numeric(env, "LOCATION_MAX_BUFFER", 10_000),
        },
    }


def apply(config):
    current.clear()
    current.update(config)
    return current


def dispatch_settings():
    return current["dispatch"]
