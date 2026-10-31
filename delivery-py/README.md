# delivery-py — сервис доставки, процедурная реализация

Замена сервиса `apps/delivery` проекта food-delivery: тот же HTTP API,
та же база `delivery_db`, те же события в `delivery.events` и канал
позиции `ws:order:{orderId}` в Redis, те же переменные окружения.

## Процедурный стиль

Классов нет (`grep -rn "^class " delivery` пуст). Данные — словари и списки,
поведение — функции над ними, состояние процесса — переменные модулей,
которые заполняются при запуске.

- **Сущности** — словари: `domain.courier_new(...)`, `domain.delivery_open(...)`.
- **Правила** — функции, которые проверяют переход и меняют словарь:
  `domain.courier_offer(courier)`, `domain.delivery_pick_up(delivery, courier_id, at)`.
- **Ошибки** — обычное `Exception` с приложенным словарём `app`
  (`errors.rule(...)`, `errors.conflict(...)`); HTTP-слой превращает его в
  ответ того же формата, что у остальных сервисов.
- **Ветвление по типу курьера** — словарь `geo.SPEED_KMH[vehicle]` вместо
  полиморфизма (в Java-версии — подклассы `FootCourier`, `BikeCourier`, `CarCourier`).
- **HTTP** — WSGI-приложение-функция `api.application(environ, start_response)`
  и таблица маршрутов `api.ROUTES`; сервер — waitress.

## Модули

| Модуль | Что делает |
|---|---|
| `config` | настройки из окружения, проверка при запуске |
| `domain` | курьер, доставка, предложение: состояния и переходы |
| `dispatch` | запрос и освобождение курьера, подбор, переназначение по таймеру |
| `couriers` | действия курьера: смена, позиция, принять/отказаться, забрал/вручил |
| `tracking` | позиция курьера для клиента, учёт курьеров администратором |
| `store` | SQL к PostGIS: подбор по индексу GiST, блокировки строк, outbox |
| `routing` | OSRM за размыкателем цепи, ранжирование кандидатов, маршрут |
| `positions`, `history` | позиция в Redis и рассылка; пакетная запись истории координат |
| `outbox`, `events` | события в Kafka (Transactional Outbox), конверт события |
| `auth`, `api` | вход по токену Keycloak, служебный токен; маршруты HTTP |
| `background`, `__main__` | фоновые потоки, запуск и остановка |
| `lastmile/` | планирование «последней мили» — см. `../docs/algorithms.md` |

## Запуск

```bash
python -m venv .venv && .venv/Scripts/pip install -r requirements-dev.txt
.venv/Scripts/python -m pytest
.venv/Scripts/python -m delivery                       # сервис, порт DELIVERY_PORT (3004)
.venv/Scripts/python -m delivery.lastmile ../data/moscow_sample.json [--json]
```

## Проверки качества

```bash
.venv/Scripts/ruff check . ../db                         # правила — ruff.toml, строка до 110
.venv/Scripts/python -X utf8 -m pytest --cov=delivery    # покрытие по модулям
.venv/Scripts/python -X utf8 ../db/check_migration.py    # миграция 007 на базе стенда
```

`tests/test_migration.py` вызывает те же функции, что `db/check_migration.py`,
на своей базе `delivery_lastmile_pytest_check`; если PostgreSQL стенда
(`localhost:25432`, переменные `CHECK_POSTGRES_*`) недоступен — тесты
пропускаются. Сценарии, покрытие и журнал ошибок — `../docs/testing.md`.

Переменные окружения — те же, что у TS-версии: `DELIVERY_PORT`,
`POSTGRES_HOST/PORT/USER/PASSWORD`, `DELIVERY_DB`, `REDIS_HOST/PORT`,
`KAFKA_BROKERS`, `KEYCLOAK_ISSUER`, `KEYCLOAK_INTERNAL_URL`, `AUTH_AUDIENCE`,
`AUTH_DISABLED` (только тесты), `DELIVERY_INTERNAL_TOKEN`,
`DISPATCH_RADIUS_METERS`, `OFFER_TTL_SECONDS`, `COURIER_FRESH_SECONDS`,
`OSRM_URL` (пусто — по прямой), `POSITION_TTL_SECONDS`,
`LOCATION_FLUSH_INTERVAL_MS`, `OUTBOX_POLL_INTERVAL_MS`.

## Отличия от TS-версии

- Нет трассировки OpenTelemetry: в TS она включается только при заданном
  `OTEL_EXPORTER_OTLP_ENDPOINT`; сквозной идентификатор `x-correlation-id`
  и его запись в события сохранены.
- `/metrics` отдаёт счётчик и суммарное время запросов по маршрутам, без
  гистограмм `prom-client`.
- Идентификатор не в формате UUID в пути (`/offers/abc/accept`) даёт 404,
  а не 500 из-за ошибки приведения типа в PostgreSQL.
