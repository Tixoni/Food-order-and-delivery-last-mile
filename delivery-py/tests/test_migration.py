"""
Физическая модель: миграция 007_last_mile.sql на чистой базе стенда.

Те же функции, что у `db/check_migration.py`: исходные миграции, 007 дважды,
затем каждое ограничение — отдельным тестом. База проверки своя
(`delivery_lastmile_pytest_check`), чтобы тесты и ручной запуск скрипта
не мешали друг другу; рабочие базы стенда не затрагиваются.

Нет сервера (стенд не поднят) или исходного проекта рядом — тесты
пропускаются, остальной набор от базы не зависит.
"""

import pytest

import check_migration as cm

SETTINGS = cm.connection_settings()
REACHABLE, REASON = cm.server_reachable(SETTINGS)
HAS_ORIGINAL = bool(cm.original_migrations(cm.original_dir()))
TEST_DB = "delivery_lastmile_pytest_check"


@pytest.fixture(scope="module")
def scratch():
    if not REACHABLE:
        pytest.skip(f"PostgreSQL стенда недоступен: {REASON}")
    if not HAS_ORIGINAL:
        pytest.skip("нет исходных миграций food-delivery рядом с репозиторием")
    conn, steps = cm.build_scratch(SETTINGS, TEST_DB)
    try:
        yield conn, steps
    finally:
        conn.close()
        cm.drop_database(SETTINGS, TEST_DB)


def test_migrations_apply_and_007_is_repeatable(scratch):
    _, steps = scratch
    assert [step["id"] for step in steps] == ["M1", "M2", "M3"], steps
    for step in steps:
        assert step["ok"], step


@pytest.mark.parametrize("check", cm.CHECKS, ids=[check["id"] for check in cm.CHECKS])
def test_constraint(scratch, check):
    conn, steps = scratch
    if not all(step["ok"] for step in steps):
        pytest.fail("миграции не применились — ограничения не проверить")
    result = cm.run_check(conn, check)
    assert result["ok"], f"{check['name']}: ожидалось {check['expected']}, получено {result['actual']}"


def test_scratch_name_guard():
    """Пересоздать можно только базу проверки, рабочую — нет (сервер для этого не нужен)."""
    with pytest.raises(ValueError):
        cm.drop_database(SETTINGS, "delivery_db")
