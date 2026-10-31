"""
Соединения с PostgreSQL: пул на процесс и транзакция как блок `with`.

Соединения работают в режиме autocommit: одиночное чтение не открывает
транзакцию, а `transaction()` открывает её явно — фиксирует при выходе
из блока и откатывает при исключении.
"""

import time
from contextlib import contextmanager

import psycopg
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

_state = {"pool": None}


def init_pool(database, max_size=10):
    conninfo = psycopg.conninfo.make_conninfo(
        host=database["host"],
        port=database["port"],
        user=database["user"],
        password=database["password"],
        dbname=database["dbname"],
        application_name="delivery-py",
    )
    _state["pool"] = ConnectionPool(
        conninfo,
        min_size=1,
        max_size=max_size,
        kwargs={"row_factory": dict_row, "autocommit": True},
        timeout=5,
        open=True,
    )


def close_pool():
    if _state["pool"] is not None:
        _state["pool"].close()
        _state["pool"] = None


@contextmanager
def connection():
    with _state["pool"].connection() as conn:
        yield conn


@contextmanager
def transaction():
    with _state["pool"].connection() as conn:
        with conn.transaction():
            yield conn


def fetch_one(conn, sql, params=()):
    return conn.execute(sql, params).fetchone()


def fetch_all(conn, sql, params=()):
    return conn.execute(sql, params).fetchall()


def execute(conn, sql, params=()):
    conn.execute(sql, params)


def readiness():
    """Проверка готовности (/ready): база отвечает на SELECT 1."""
    started = time.monotonic()
    try:
        with connection() as conn:
            conn.execute("SELECT 1")
        return {"status": "ok", "latencyMs": round((time.monotonic() - started) * 1000)}
    except Exception as error:  # noqa: BLE001 — любой отказ базы = «не готов»
        return {
            "status": "fail",
            "latencyMs": round((time.monotonic() - started) * 1000),
            "error": str(error),
        }
