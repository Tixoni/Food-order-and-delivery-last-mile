"""
Проверка физической модели: миграция 007_last_mile.sql на чистой базе.

    delivery-py/.venv/Scripts/python -X utf8 db/check_migration.py [--keep]

Что делает:
    1. подключается к PostgreSQL стенда практики (localhost:25432, app/app —
       контейнер fdlm-postgres); переопределяется переменными CHECK_POSTGRES_*;
    2. пересоздаёт ОТДЕЛЬНУЮ базу delivery_lastmile_check — рабочие базы
       стенда (delivery_db и другие) не затрагиваются;
    3. включает postgis и pgcrypto, как требуют исходные миграции
       (geography, gen_random_uuid), и применяет их по порядку:
       common/001, delivery/001–006 исходного проекта, затем 007 — дважды,
       сравнивая схему после первого и второго запуска (повторяемость);
    4. проверяет ограничения: недопустимые строки база должна отклонить
       именно тем ограничением, которое за это отвечает, а допустимые — принять;
    5. печатает таблицу результатов и удаляет базу (с --keep — оставляет
       для просмотра в psql).

Каждая проверка идёт в своей транзакции и откатывается, поэтому проверки
не зависят друг от друга. Отклонение засчитывается, только если сработало
ожидаемое ограничение: строка, отклонённая «не тем» (например, NOT NULL из-за
ошибки в самой проверке), не должна выдавать себя за проверенное правило.

Те же функции вызывают тесты delivery-py/tests/test_migration.py.
Код возврата: 0 — всё прошло, 1 — есть провалы, 2 — нет сервера или миграций.
"""

import os
import sys
from pathlib import Path

import psycopg

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "db" / "migrations" / "007_last_mile.sql"
SCRATCH_DB = "delivery_lastmile_check"
# Исходные миграции нужны базе, а не PostGIS: расширения включает проверка.
EXTENSIONS = ("postgis", "pgcrypto")

PICKUP = "ST_GeogFromText('SRID=4326;POINT(37.6100 55.7500)')"
DROPOFF = "ST_GeogFromText('SRID=4326;POINT(37.6200 55.7600)')"
# Квадрат вокруг точки выдачи и «бабочка» — самопересекающийся контур.
SQUARE = ("ST_GeogFromText('SRID=4326;POLYGON((37.60 55.74, 37.62 55.74, 37.62 55.76, "
          "37.60 55.76, 37.60 55.74))')")
BOWTIE = ("ST_GeogFromText('SRID=4326;POLYGON((37.60 55.74, 37.62 55.76, 37.62 55.74, "
          "37.60 55.76, 37.60 55.74))')")


# ─────────────────────────── подключение и база ───────────────────────────


def connection_settings(env=None):
    env = os.environ if env is None else env
    return {
        "host": env.get("CHECK_POSTGRES_HOST", "localhost"),
        "port": int(env.get("CHECK_POSTGRES_PORT", "25432")),
        "user": env.get("CHECK_POSTGRES_USER", "app"),
        "password": env.get("CHECK_POSTGRES_PASSWORD", "app"),
    }


def connect(settings, dbname):
    return psycopg.connect(dbname=dbname, autocommit=True, connect_timeout=3, **settings)


def server_reachable(settings):
    """`(True, "")` — сервер отвечает; иначе `(False, причина)`."""
    try:
        with connect(settings, "postgres") as conn:
            conn.execute("SELECT 1")
        return True, ""
    except psycopg.Error as error:
        return False, str(error).strip().splitlines()[0] if str(error).strip() else type(error).__name__


def original_dir(env=None):
    """Исходный проект food-delivery — только чтение его миграций."""
    env = os.environ if env is None else env
    return Path(env.get("FOOD_DELIVERY_DIR", ROOT.parent / "food-delivery"))


def original_migrations(project_dir):
    """Порядок как у migrate.mjs исходного проекта: общие, затем миграции delivery_db."""
    folder = Path(project_dir) / "infra" / "migrations"
    return sorted((folder / "common").glob("*.sql")) + sorted((folder / "delivery").glob("*.sql"))


def _guard(name):
    # Защита от опечатки: пересоздаётся только база проверки, не рабочая.
    if not name.endswith("_check"):
        raise ValueError(f"база {name} — не база проверки (имя должно оканчиваться на _check)")


def drop_database(settings, name):
    _guard(name)
    with connect(settings, "postgres") as conn:
        # FORCE закрывает соединения, оставшиеся от прерванного запуска.
        conn.execute(f'DROP DATABASE IF EXISTS "{name}" WITH (FORCE)')


def recreate_database(settings, name):
    drop_database(settings, name)
    with connect(settings, "postgres") as conn:
        conn.execute(f'CREATE DATABASE "{name}"')
    with connect(settings, name) as conn:
        for extension in EXTENSIONS:
            conn.execute(f"CREATE EXTENSION IF NOT EXISTS {extension}")


def apply_file(conn, path):
    """Файл миграции целиком в одной транзакции, как в migrate.mjs: ошибка откатывает весь файл."""
    with conn.transaction():
        conn.execute(Path(path).read_text(encoding="utf-8"))


def schema_snapshot(conn):
    """Колонки, ограничения, индексы, триггеры и комментарии схемы public — для сравнения запусков."""
    rows = conn.execute(
        """SELECT 'column', table_name || '.' || column_name,
                  data_type || ' ' || is_nullable || ' ' || coalesce(column_default, '')
             FROM information_schema.columns WHERE table_schema = 'public'
           UNION ALL
           SELECT 'constraint', conrelid::regclass::text || '.' || conname, pg_get_constraintdef(oid)
             FROM pg_constraint WHERE connamespace = 'public'::regnamespace
           UNION ALL
           SELECT 'index', indexname, indexdef FROM pg_indexes WHERE schemaname = 'public'
           UNION ALL
           SELECT 'trigger', tgrelid::regclass::text || '.' || tgname, pg_get_triggerdef(oid)
             FROM pg_trigger WHERE NOT tgisinternal
           UNION ALL
           SELECT 'comment', objoid::regclass::text || '.' || objsubid, description
             FROM pg_description WHERE classoid = 'pg_class'::regclass
           UNION ALL
           SELECT 'rows', 'vehicle_types', count(*)::text FROM vehicle_types
           ORDER BY 1, 2, 3"""
    ).fetchall()
    return [tuple(row) for row in rows]


def _row(check_id, group, name, expected, actual, ok):
    return {"id": check_id, "group": group, "name": name, "expected": expected, "actual": actual, "ok": ok}


def build_scratch(settings, name, project_dir=None):
    """
    Чистая база с исходными миграциями и дважды применённой 007.
    Возвращает `(соединение, строки результата)`; соединение закрывает вызывающий.
    """
    files = original_migrations(project_dir or original_dir())
    recreate_database(settings, name)
    conn = connect(settings, name)
    steps = []
    try:
        for path in files:
            apply_file(conn, path)
        steps.append(_row(
            "M1", "миграция", "исходные common/001 и delivery/001–006",
            f"{len(files)} файлов без ошибок", f"применено {len(files)}", len(files) == 7,
        ))
        apply_file(conn, MIGRATION)
        first = schema_snapshot(conn)
        steps.append(_row("M2", "миграция", "007_last_mile.sql", "без ошибок", "применена", True))
        apply_file(conn, MIGRATION)
        second = schema_snapshot(conn)
        changed = sorted(set(first) ^ set(second))
        steps.append(_row(
            "M3", "миграция", "007 повторно (повторяемость)", "без ошибок, схема та же",
            "схема та же" if not changed else f"изменилось: {changed[:3]}", not changed,
        ))
    except psycopg.Error as error:
        steps.append(_row("M0", "миграция", "применение миграций", "без ошибок", str(error).strip(), False))
    return conn, steps


# ─────────────────────────── данные для проверок ───────────────────────────


def _one(conn, sql, params=()):
    return conn.execute(sql, params).fetchone()[0]


def _courier(conn, vehicle="bike"):
    return _one(conn, "INSERT INTO couriers (full_name, vehicle_type) VALUES (%s, %s) RETURNING id",
                (f"Курьер ({vehicle})", vehicle))


def _delivery(conn, status="PENDING", courier_id=None, order_id=None):
    """Доставка в формате текущего сервиса: без окна и размера."""
    return _one(
        conn,
        f"""INSERT INTO deliveries (order_id, courier_id, status, pickup_location, dropoff_location)
            VALUES (coalesce(%s, gen_random_uuid()), %s, %s, {PICKUP}, {DROPOFF}) RETURNING id""",
        (order_id, courier_id, status),
    )


def _route(conn, courier_id, status="PLANNED"):
    return _one(conn, "INSERT INTO routes (courier_id, status) VALUES (%s, %s) RETURNING id",
                (courier_id, status))


def _stops(conn, route_id, stops):
    """`stops` — [(позиция, доставка, вид)], одной командой."""
    for position, delivery_id, kind in stops:
        conn.execute(
            "INSERT INTO route_stops (route_id, position, delivery_id, kind) VALUES (%s, %s, %s, %s)",
            (route_id, position, delivery_id, kind),
        )


def _rejected(conn, constraint, action):
    """
    Выполнить `action(conn)` в точке сохранения и убедиться, что база отклонила
    его ограничением `constraint`. Отложенные проверки форсируются до фиксации.
    """
    try:
        with conn.transaction():
            action(conn)
            conn.execute("SET CONSTRAINTS ALL IMMEDIATE")
    except psycopg.errors.IntegrityError as error:
        actual = error.diag.constraint_name
        return actual == constraint, f"отклонено: {actual} ({error.diag.sqlstate})"
    return False, "принято"


# ─────────────────────────── проверки: отказ ───────────────────────────


def check_window_equal(conn):
    delivery_id = _delivery(conn)
    return _rejected(conn, "ck_deliveries_window", lambda c: c.execute(
        "UPDATE deliveries SET window_start = %s, window_end = %s WHERE id = %s",
        ("2026-11-03 12:00+03", "2026-11-03 12:00+03", delivery_id),
    ))


def check_window_reversed(conn):
    delivery_id = _delivery(conn)
    return _rejected(conn, "ck_deliveries_window", lambda c: c.execute(
        "UPDATE deliveries SET window_start = %s, window_end = %s WHERE id = %s",
        ("2026-11-03 13:00+03", "2026-11-03 12:00+03", delivery_id),
    ))


def check_window_half(conn):
    delivery_id = _delivery(conn)
    return _rejected(conn, "ck_deliveries_window_pair", lambda c: c.execute(
        "UPDATE deliveries SET window_start = %s WHERE id = %s", ("2026-11-03 12:00+03", delivery_id),
    ))


def check_size_zero(conn):
    delivery_id = _delivery(conn)
    return _rejected(conn, "ck_deliveries_size", lambda c: c.execute(
        "UPDATE deliveries SET size = 0 WHERE id = %s", (delivery_id,),
    ))


def check_duplicate_stop(conn):
    route_id, delivery_id = _route(conn, _courier(conn)), _delivery(conn)
    _stops(conn, route_id, [(1, delivery_id, "PICKUP"), (2, delivery_id, "DROPOFF")])
    return _rejected(conn, "ux_route_stops_delivery_kind",
                     lambda c: _stops(c, route_id, [(3, delivery_id, "DROPOFF")]))


def check_duplicate_position(conn):
    route_id, first, second = _route(conn, _courier(conn)), _delivery(conn), _delivery(conn)
    _stops(conn, route_id, [(1, first, "PICKUP")])
    return _rejected(conn, "route_stops_pkey", lambda c: _stops(c, route_id, [(1, second, "PICKUP")]))


def check_dropoff_before_pickup(conn):
    route_id, delivery_id = _route(conn, _courier(conn)), _delivery(conn)
    _stops(conn, route_id, [(1, delivery_id, "DROPOFF")])
    return _rejected(conn, "trg_route_stops_pickup_before_dropoff",
                     lambda c: _stops(c, route_id, [(2, delivery_id, "PICKUP")]))


def check_second_active_route(conn):
    courier_id = _courier(conn)
    _route(conn, courier_id, "ACTIVE")
    return _rejected(conn, "ux_routes_courier_active", lambda c: _route(c, courier_id, "ACTIVE"))


def check_unknown_vehicle(conn):
    # Первым срабатывает CHECK из 001 (см. комментарий в 007): курьер с типом,
    # которого нет в коде сервиса, в базу не попадает.
    return _rejected(conn, "couriers_vehicle_type_check", lambda c: _courier(c, "scooter"))


def check_vehicle_in_use(conn):
    _courier(conn, "bike")
    return _rejected(conn, "fk_couriers_vehicle_type", lambda c: c.execute(
        "DELETE FROM vehicle_types WHERE code = 'bike'",
    ))


def check_invalid_zone(conn):
    return _rejected(conn, "ck_zones_area_valid", lambda c: c.execute(
        f"INSERT INTO zones (name, area) VALUES ('Бабочка', {BOWTIE})",
    ))


def check_completed_before_arrival(conn):
    route_id, delivery_id = _route(conn, _courier(conn)), _delivery(conn)
    _stops(conn, route_id, [(1, delivery_id, "PICKUP")])
    return _rejected(conn, "ck_route_stops_completed_after_arrival", lambda c: c.execute(
        "UPDATE route_stops SET arrived_at = %s, completed_at = %s WHERE route_id = %s",
        ("2026-11-03 12:10+03", "2026-11-03 12:05+03", route_id),
    ))


def check_second_delivery_after_delivered(conn):
    # Ограничение исходной схемы (002): вручённая доставка тоже держит заказ.
    # На этом основана ошибка №2 журнала docs/testing.md.
    courier_id = _courier(conn)
    order_id = _one(conn, "SELECT gen_random_uuid()")
    _delivery(conn, "DELIVERED", courier_id, order_id)
    return _rejected(conn, "ux_deliveries_order_active", lambda c: _delivery(c, order_id=order_id))


# ─────────────────────────── проверки: приём ───────────────────────────


def check_vehicle_types_seeded(conn):
    rows = conn.execute("SELECT code, speed_kmh::float, capacity FROM vehicle_types ORDER BY code").fetchall()
    actual = ", ".join(f"{code} {speed:g}/{capacity}" for code, speed, capacity in rows)
    return actual == "bike 14/3, car 20/6, foot 5/2", actual


def check_service_style_delivery(conn):
    delivery_id = _delivery(conn)
    size, start, end = conn.execute(
        "SELECT size, window_start, window_end FROM deliveries WHERE id = %s", (delivery_id,)
    ).fetchone()
    window = "окна нет" if start is None and end is None else f"окно {start}–{end}"
    return (size, start, end) == (1, None, None), f"size={size}, {window}"


def check_delivery_with_window(conn):
    delivery_id = _delivery(conn)
    conn.execute(
        "UPDATE deliveries SET window_start = %s, window_end = %s, size = 2 WHERE id = %s",
        ("2026-11-03 12:00+03", "2026-11-03 12:30+03", delivery_id),
    )
    minutes = _one(conn, "SELECT extract(epoch FROM window_end - window_start) / 60 FROM deliveries "
                         "WHERE id = %s", (delivery_id,))
    return float(minutes) == 30, f"принято, окно {float(minutes):g} мин, 2 места"


def check_interleaved_route(conn):
    route_id, first, second = _route(conn, _courier(conn), "ACTIVE"), _delivery(conn), _delivery(conn)
    _stops(conn, route_id, [(1, first, "PICKUP"), (2, second, "PICKUP"),
                            (3, second, "DROPOFF"), (4, first, "DROPOFF")])
    count = _one(conn, "SELECT count(*) FROM route_stops WHERE route_id = %s", (route_id,))
    return count == 4, f"принято, точек {count}"


def check_dropoff_only_route(conn):
    route_id, delivery_id = _route(conn, _courier(conn)), _delivery(conn)
    _stops(conn, route_id, [(1, delivery_id, "DROPOFF")])
    return True, "принято"


def check_route_lifecycle(conn):
    courier_id = _courier(conn)
    active = _route(conn, courier_id, "ACTIVE")
    _route(conn, courier_id, "PLANNED")
    conn.execute("UPDATE routes SET status = 'DONE' WHERE id = %s", (active,))
    _route(conn, courier_id, "ACTIVE")
    count = _one(conn, "SELECT count(*) FROM routes WHERE courier_id = %s", (courier_id,))
    return count == 3, f"принято, маршрутов {count}"


def check_deferred_reorder(conn):
    route_id, first, second = _route(conn, _courier(conn)), _delivery(conn), _delivery(conn)
    _stops(conn, route_id, [(1, first, "PICKUP"), (2, first, "DROPOFF"),
                            (3, second, "PICKUP"), (4, second, "DROPOFF")])

    def reorder(c, deferred):
        if deferred:
            c.execute("SET CONSTRAINTS trg_route_stops_pickup_before_dropoff DEFERRED")
        # Обмен пар через свободные позиции 50 и 51. Уже первый шаг временно
        # недопустим: забор первого заказа уходит на 50, а его вручение ещё на 2.
        c.execute("UPDATE route_stops SET position = 50 WHERE route_id = %s AND position = 1", (route_id,))
        c.execute("UPDATE route_stops SET position = 51 WHERE route_id = %s AND position = 2", (route_id,))
        c.execute("UPDATE route_stops SET position = 1 WHERE route_id = %s AND position = 3", (route_id,))
        c.execute("UPDATE route_stops SET position = 2 WHERE route_id = %s AND position = 4", (route_id,))
        c.execute("UPDATE route_stops SET position = 3 WHERE route_id = %s AND position = 50", (route_id,))
        c.execute("UPDATE route_stops SET position = 4 WHERE route_id = %s AND position = 51", (route_id,))

    # Без отсрочки триггер отклоняет первый же шаг; с отсрочкой проверяется
    # только итог: P2@1 D2@2 P1@3 D1@4.
    immediate_ok, immediate = _rejected(
        conn, "trg_route_stops_pickup_before_dropoff", lambda c: reorder(c, deferred=False)
    )
    try:
        with conn.transaction():
            reorder(conn, deferred=True)
            conn.execute("SET CONSTRAINTS ALL IMMEDIATE")
            order = conn.execute(
                "SELECT string_agg(kind || '@' || position, ' ' ORDER BY position) FROM route_stops "
                "WHERE route_id = %s AND delivery_id = %s", (route_id, second),
            ).fetchone()[0]
        deferred = f"с отсрочкой принято ({order})"
        deferred_ok = order == "PICKUP@1 DROPOFF@2"
    except psycopg.Error as error:
        deferred, deferred_ok = f"с отсрочкой отклонено: {error.diag.constraint_name}", False
    return immediate_ok and deferred_ok, f"без отсрочки {immediate}; {deferred}"


def check_zone_index(conn):
    zone_id = _one(conn, f"INSERT INTO zones (name, area) VALUES ('Центр', {SQUARE}) RETURNING id")
    conn.execute(
        "INSERT INTO courier_zones (courier_id, zone_id) VALUES (%s, %s)", (_courier(conn, "foot"), zone_id)
    )
    query = f"SELECT id FROM zones WHERE ST_Covers(area, {PICKUP})"
    found = conn.execute(query).fetchall()
    # Таблица крошечная, и без запрета планировщик выбрал бы полный просмотр;
    # проверяется, что индекс к такому запросу вообще применим.
    conn.execute("SET LOCAL enable_seqscan = off")
    plan = "\n".join(row[0] for row in conn.execute("EXPLAIN " + query).fetchall())
    uses_index = "ix_zones_area" in plan
    return len(found) == 1 and uses_index, (
        f"точка в зоне: {len(found)}; ST_Covers по индексу ix_zones_area: {'да' if uses_index else 'нет'}"
    )


def _check(check_id, group, name, expected, function):
    return {"id": check_id, "group": group, "name": name, "expected": expected, "function": function}


REJECT = "отказ"
ACCEPT = "приём"

CHECKS = [
    _check("N1", REJECT, "окно: начало = конец", "ck_deliveries_window", check_window_equal),
    _check("N2", REJECT, "окно: начало позже конца", "ck_deliveries_window", check_window_reversed),
    _check("N3", REJECT, "окно задано наполовину", "ck_deliveries_window_pair", check_window_half),
    _check("N4", REJECT, "размер заказа 0", "ck_deliveries_size", check_size_zero),
    _check("N5", REJECT, "повтор точки (заказ и вид те же)", "ux_route_stops_delivery_kind",
           check_duplicate_stop),
    _check("N6", REJECT, "две точки на одной позиции", "route_stops_pkey", check_duplicate_position),
    _check("N7", REJECT, "вручение раньше забора", "trg_route_stops_pickup_before_dropoff",
           check_dropoff_before_pickup),
    _check("N8", REJECT, "второй ACTIVE-маршрут курьера", "ux_routes_courier_active",
           check_second_active_route),
    _check("N9", REJECT, "неизвестный тип транспорта", "couriers_vehicle_type_check", check_unknown_vehicle),
    _check("N10", REJECT, "удалить тип транспорта, на котором работают", "fk_couriers_vehicle_type",
           check_vehicle_in_use),
    _check("N11", REJECT, "самопересекающийся полигон зоны", "ck_zones_area_valid", check_invalid_zone),
    _check("N12", REJECT, "точка закрыта раньше прибытия", "ck_route_stops_completed_after_arrival",
           check_completed_before_arrival),
    _check("N13", REJECT, "вторая доставка заказа, первая вручена", "ux_deliveries_order_active",
           check_second_delivery_after_delivered),
    _check("P1", ACCEPT, "справочник транспорта", "foot 5/2, bike 14/3, car 20/6",
           check_vehicle_types_seeded),
    _check("P2", ACCEPT, "доставка в формате текущего сервиса", "size=1, окна нет",
           check_service_style_delivery),
    _check("P3", ACCEPT, "доставка с окном 30 мин и 2 местами", "принято", check_delivery_with_window),
    _check("P4", ACCEPT, "маршрут P1 P2 D2 D1", "принято", check_interleaved_route),
    _check("P5", ACCEPT, "маршрут из одного вручения (заказ на руках)", "принято", check_dropoff_only_route),
    _check("P6", ACCEPT, "ACTIVE + PLANNED; после DONE новый ACTIVE", "принято", check_route_lifecycle),
    _check("P7", ACCEPT, "перестановка точек в одной транзакции", "сразу — отказ, отложенно — приём",
           check_deferred_reorder),
    _check("P8", ACCEPT, "зона, курьер в зоне, поиск по GiST", "найдена по индексу", check_zone_index),
]


def run_check(conn, check):
    """Одна проверка в своей транзакции; всё, что она записала, откатывается."""
    try:
        with conn.transaction(force_rollback=True):
            ok, actual = check["function"](conn)
    except psycopg.Error as error:
        ok, actual = False, f"ошибка: {str(error).strip()}"
    return _row(check["id"], check["group"], check["name"], check["expected"], actual, ok)


def run_all(settings, name=SCRATCH_DB, keep=False):
    conn, results = build_scratch(settings, name)
    try:
        if all(step["ok"] for step in results):
            results += [run_check(conn, check) for check in CHECKS]
    finally:
        conn.close()
        if not keep:
            drop_database(settings, name)
    return results


def format_table(results):
    headers = ("", "ID", "Проверка", "Ожидается", "Получено")
    rows = [("ОК" if r["ok"] else "ПРОВАЛ", r["id"], r["name"], r["expected"], r["actual"]) for r in results]
    widths = [max(len(str(row[i])) for row in [headers, *rows]) for i in range(len(headers) - 1)]

    def line(row):
        cells = [str(cell).ljust(width) for cell, width in zip(row, widths, strict=False)]
        return "  ".join([*cells, str(row[-1])])

    passed = sum(r["ok"] for r in results)
    summary = f"Пройдено {passed} из {len(results)}."
    return "\n".join([line(headers), *(line(row) for row in rows), "", summary])


def main(argv):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    settings = connection_settings()
    reachable, reason = server_reachable(settings)
    if not reachable:
        print(f"PostgreSQL {settings['host']}:{settings['port']} недоступен: {reason}")
        print("Стенд практики: контейнер fdlm-postgres (scripts/stand.sh up).")
        return 2
    if not original_migrations(original_dir()):
        print(f"Нет исходных миграций в {original_dir()}/infra/migrations (переменная FOOD_DELIVERY_DIR).")
        return 2
    keep = "--keep" in argv
    results = run_all(settings, SCRATCH_DB, keep=keep)
    print(f"База проверки: {SCRATCH_DB} на {settings['host']}:{settings['port']}"
          + (" (оставлена)" if keep else " (удалена после проверки)"))
    print()
    print(format_table(results))
    return 0 if all(result["ok"] for result in results) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
