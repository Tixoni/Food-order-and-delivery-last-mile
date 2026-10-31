# Тестирование и качество кода

Материал к этапу 5: тестовые сценарии, где каждый из них автоматизирован,
проверка физической модели данных, оценка качества кода и журнал ошибок,
найденных и исправленных за этап.

## Как запускать

Из папки `delivery-py`:

```bash
.venv/Scripts/python -X utf8 -m pytest                         # все тесты
.venv/Scripts/python -X utf8 -m pytest --cov=delivery          # с покрытием по модулям
.venv/Scripts/ruff check . ../db                               # стиль и типичные ошибки
.venv/Scripts/python -X utf8 ../db/check_migration.py [--keep] # физическая модель на чистой базе
```

Тесты `test_migration.py` и скрипт `db/check_migration.py` работают с
PostgreSQL стенда практики (`localhost:25432`, контейнер `fdlm-postgres`).
Если сервер не поднят, тесты пропускаются (`skipped`), а скрипт сообщает,
что сервер недоступен, и завершается с кодом 2. Проверка создаёт свою базу
(`delivery_lastmile_check`, у тестов — `delivery_lastmile_pytest_check`) и
удаляет её после себя; рабочие базы стенда не затрагиваются.

## Тестовые сценарии

Обозначения в колонке «Где»: `модуль::тест` — тест pytest в
`delivery-py/tests/`; `N…`, `P…`, `M…` — проверки `db/check_migration.py`
(они же параметры теста `test_migration.py::test_constraint`); e2e —
сквозная проверка `infra/verification/delivery-e2e.mjs` исходного проекта.

### Правила предметной области

| ID | Что проверяется | Входные данные | Ожидаемый результат | Где |
|---|---|---|---|---|
| D-01 | Смена только у привязанного и активного курьера | курьер без учётной записи; отключённый курьер | 422 DOMAIN_RULE_VIOLATED | `test_domain::test_shift_requires_account_and_active_courier` |
| D-02 | Повторное «на смену» ничего не меняет | два вызова подряд | `True`, затем `False`; статус `available` | `test_domain::test_shift_is_idempotent` |
| D-03 | С заказом на руках со смены не уйти | курьер `busy` | 422, статус не изменился | `test_domain::test_cannot_leave_shift_with_order` |
| D-04 | Цикл курьера: предложение → принял → свободен | курьер `available` | `assigned` → `busy` → `available` | `test_domain::test_offer_cycle_of_courier` |
| D-05 | Принять можно только предложенный заказ | курьер `available` принимает | 422 | `test_domain::test_only_offered_order_can_be_accepted` |
| D-06 | «Доставил» только после «забрал»; повтор отметки безопасен | доставка `ASSIGNED` | 422 на «доставил»; «забрал» `True`, `False`; затем `DELIVERED` | `test_domain::test_delivered_only_after_picked_up` |
| D-07 | Чужой курьер не трогает доставку | курьер c-2, доставка c-1 | 409 | `test_domain::test_foreign_courier_cannot_touch_delivery` |
| D-08 | Принятый заказ второй раз не принять | два курьера принимают | 409 второму | `test_domain::test_accepted_delivery_cannot_be_accepted_again` |
| D-09 | Вручённый заказ не отменяется | доставка `DELIVERED` | `False`, статус прежний | `test_domain::test_delivered_order_is_not_cancelled` |
| D-10 | Новый запрос после «не найден» — новый раунд поиска | новый ключ через 5 мин | ключ, раунд обновлены, отметка «не найден» снята | `test_domain::test_new_request_starts_new_search_round` |
| D-11 | Просроченное предложение не принять | ответ ровно через 90 с | 409, предложение открыто | `test_domain::test_expired_offer_cannot_be_accepted` |
| D-12 | На предложение отвечает только адресат и один раз | чужой ответ; повторный ответ | 409, 409; таймер не перезаписывает ответ | `test_domain::test_offer_answered_once_and_only_by_addressee` |
| D-13 | Повторный запрос курьера по заказу с вручённой доставкой | доставка вручена, вставка упирается в индекс | 409 после одного повтора, а не бесконечный цикл | `test_reliability::test_duplicate_after_delivered_is_conflict_not_endless_retry` |
| D-14 | Одновременный запрос успел первым | первая вставка — дубль, повтор находит доставку | ответ этой доставкой | `test_reliability::test_concurrent_duplicate_answers_with_existing_delivery` |

### HTTP, вход и права

| ID | Что проверяется | Входные данные | Ожидаемый результат | Где |
|---|---|---|---|---|
| H-01 | Проверка здоровья открыта | `GET /health` без токена | 200, `status: ok` | `test_api::test_health_is_public` |
| H-02 | Неизвестный маршрут — ответ как у Nest | `GET /nope` | 404, `Cannot GET /nope` | `test_api::test_unknown_route_is_404_like_nest` |
| H-03 | Внутренний API — только со служебным токеном | `POST /deliveries` без токена | 401 UNAUTHORIZED | `test_api::test_internal_api_requires_service_token` |
| H-04 | Проверка тела запроса курьера | `orderId: "x"` | 400 VALIDATION_FAILED | `test_api::test_internal_api_validates_request` |
| H-05 | Маршруты курьера требуют входа | `GET /couriers/me` без токена | 401 | `test_api::test_courier_routes_require_login` |
| H-06 | Роль проверяется | клиент на `GET /couriers` | 403, в подробностях нужные и имеющиеся роли | `test_api::test_role_is_checked` |
| H-07 | Служебные роли провайдера отбрасываются | роли `courier`, `uma_authorization` | остаётся `courier` | `test_api::test_unknown_roles_are_dropped` |
| H-08 | Флаг смены — только логический | `onShift: "yes"` | 400 | `test_api::test_shift_needs_boolean` |
| H-09 | Сквозной идентификатор: безопасный возвращается, опасный заменяется | `abc-123`; `bad id <script>` | первый в ответе; второй заменён новым | `test_api::test_correlation_id_is_echoed_when_safe` |
| H-10 | Сбой не раскрывает подробности | исключение с «секретом» | 500, общее сообщение без текста исключения | `test_api::test_service_failure_hides_details` |
| H-11 | Метрики считают запросы | `/health`, затем `/metrics` | 200, есть `route="/health"` | `test_api::test_metrics_count_requests` |
| H-12 | В production вход не выключить | `NODE_ENV=production`, `AUTH_DISABLED=true`; dev-токен | отказ запуска | `test_api::test_production_refuses_disabled_auth` |
| H-13 | Персональные данные в журнале маскируются | телефон, адрес, токен во вложенном словаре | `***`; прочие поля как есть | `test_reliability::test_log_masks_personal_data` |

### Маршрутизатор и отказы (REL-2)

| ID | Что проверяется | Входные данные | Ожидаемый результат | Где |
|---|---|---|---|---|
| R-01 | Размыкатель цепи: три отказа подряд | OSRM недоступен, 4 вызова | 502 на каждый; в сеть ушли только 3, цепь `open` | `test_reliability::test_breaker_opens_after_three_failures` |
| R-02 | «Дороги нет» — штатный ответ, не отказ | 5 ответов `NoRoute` | `None`, цепь `closed` | `test_reliability::test_no_road_is_not_a_failure` |
| R-03 | Без маршрутизатора подбор идёт по прямой | `OSRM_URL` пуст, 2 кандидата | ранжирования нет (`None`) — ближайший по индексу | `test_reliability::test_without_router_ranking_falls_back_to_straight_line` |

### Алгоритмы «последней мили»

| ID | Что проверяется | Входные данные | Ожидаемый результат | Где |
|---|---|---|---|---|
| A-01 | Дейкстра находит кратчайший путь, а не прямое ребро | ребро a–e 5 км и путь через b, c, d 4 км | 4000 м, путь a-b-c-d-e | `test_lastmile::test_dijkstra_finds_shortest_path` |
| A-02 | Одностороннее движение | a→b 1 км только туда | туда 1000 м, обратно 1300 м | `test_lastmile::test_oneway_street_changes_distance_back` |
| A-03 | **Недостижимая точка** в графе | узел-«остров» | нет в расстояниях, путь `[]`, в матрице `∞` | `test_lastmile::test_unreachable_node_is_absent` |
| A-04 | Привязка адреса к ближайшему узлу | точка рядом с b | `b` | `test_lastmile::test_nearest_node_snaps_point_to_street` |
| A-05 | Ребро к неизвестному узлу | `a → zzz` | `ValueError` | `test_lastmile::test_bad_edge_is_rejected` |
| A-06 | Порядок «забрал → вручил» и вместимость | 3 заказа, вместимость 2 | маршрут допустим, загрузка ≤ 2 | `test_lastmile::test_route_keeps_pickup_before_dropoff_and_capacity` |
| A-07 | Ожидание и опоздание | готовность на 5-й мин, окно до 10-й, путь 20 мин | старт забора 5, опоздание 15 мин | `test_lastmile::test_evaluation_counts_lateness_and_waiting` |
| A-08 | Вручение раньше забора недопустимо | остановки в обратном порядке | `valid = False` | `test_lastmile::test_dropoff_before_pickup_is_invalid` |
| A-09 | 2-opt не ухудшает маршрут и не теряет точки | 3 заказа, машина | ключ не хуже; тот же набор остановок | `test_lastmile::test_two_opt_never_makes_route_worse` |
| A-10 | Пустой маршрут | заказов нет | допустим, 0 м | `test_lastmile::test_empty_route_is_valid` |
| A-11 | **Перегруз курьера** | заказ на 5 мест, вместимость 2 | не распределён: «перегруз» | `test_lastmile::test_overloaded_order_is_not_assigned` |
| A-12 | **Недостижимая точка вручения** | вручение на «острове» | не распределён: «точка вручения недостижима» | `test_lastmile::test_unreachable_dropoff_is_not_assigned` |
| A-13 | **Окно, в которое не успеть** | путь 20 мин, окно до 5-й мин | не распределён: «не успевает в окно» | `test_lastmile::test_impossible_window_is_not_assigned` |
| A-14 | Заказ — курьеру с наименьшей добавкой пути | два курьера, ближний и дальний | заказ ближнему | `test_lastmile::test_order_goes_to_courier_who_adds_least_travel` |
| A-15 | **Пустой заказ** | `size = 0` | `ValueError` «пустой заказ» | `test_lastmile::test_empty_order_is_rejected_on_input` |
| A-16 | Окно задом наперёд | `[30, 10]` | `ValueError` «окно» | `test_lastmile::test_bad_window_is_rejected_on_input` |
| A-17 | Окно нулевой длины — как в базе | `[30, 30]` | `ValueError` «окно» | `test_lastmile::test_zero_length_window_is_rejected_like_in_database` |
| A-18 | **Нет курьеров** | заказ, курьеров 0 | не распределён: «нет курьеров на смене» | `test_lastmile::test_no_couriers_gives_honest_reason` |
| A-19 | Весь план на тестовом наборе | `data/moscow_sample.json`: 6 курьеров, 20 заказов | 18 распределено, опозданий нет, каждый вручён один раз; Z19, Z20 — без курьера | `test_lastmile::test_sample_plan_is_consistent` |
| A-20 | Запуск из командной строки | файл набора; без аргументов | отчёт «распределено: 18», «Z20: перегруз»; код 2 без файла | `test_lastmile::test_cli_prints_report` |

### Физическая модель (миграция 007)

Отказ засчитывается, только если строку отклонило именно ожидаемое
ограничение (имя берётся из ответа сервера), а не любое другое.

| ID | Что проверяется | Входные данные | Ожидаемый результат | Где |
|---|---|---|---|---|
| M1 | Исходная схема | common/001, delivery/001–006 | 7 файлов без ошибок | `check_migration` M1, `test_migration::test_migrations_apply_and_007_is_repeatable` |
| M2 | Миграция 007 | на схему исходного проекта | без ошибок | M2, там же |
| M3 | Повторяемость 007 | второй запуск | без ошибок; колонки, ограничения, индексы, триггеры, комментарии и справочник совпадают | M3, там же |
| N1 | Окно: начало = конец (граничное значение) | 12:00–12:00 | отказ `ck_deliveries_window` | N1 |
| N2 | Окно: начало позже конца | 13:00–12:00 | отказ `ck_deliveries_window` | N2 |
| N3 | Окно задано наполовину | только начало | отказ `ck_deliveries_window_pair` | N3 |
| N4 | Размер заказа 0 (граничное значение) | `size = 0` | отказ `ck_deliveries_size` | N4 |
| N5 | Повтор точки | второе вручение того же заказа | отказ `ux_route_stops_delivery_kind` | N5 |
| N6 | Две точки на одной позиции | позиция 1 дважды | отказ `route_stops_pkey` | N6 |
| N7 | Вручение раньше забора | DROPOFF@1, затем PICKUP@2 | отказ триггером `trg_route_stops_pickup_before_dropoff` | N7 |
| N8 | Второй ACTIVE-маршрут курьера | два ACTIVE | отказ `ux_routes_courier_active` | N8 |
| N9 | Неизвестный тип транспорта | `scooter` | отказ `couriers_vehicle_type_check` | N9 |
| N10 | Удалить используемый тип транспорта | `DELETE bike` при курьере на велосипеде | отказ `fk_couriers_vehicle_type` | N10 |
| N11 | Самопересекающийся полигон зоны | «бабочка» | отказ `ck_zones_area_valid` | N11 |
| N12 | Точка закрыта раньше прибытия | прибыл 12:10, закрыл 12:05 | отказ `ck_route_stops_completed_after_arrival` | N12 |
| N13 | Вторая доставка заказа, первая вручена (схема 002) | новая доставка того же заказа | отказ `ux_deliveries_order_active` | N13 |
| P1 | Справочник транспорта заполнен | — | foot 5/2, bike 14/3, car 20/6 | P1 |
| P2 | Совместимость с текущим сервисом | вставка без окна и размера | принято, `size = 1`, окна нет | P2 |
| P3 | Доставка с окном и размером | окно 30 мин, 2 места | принято | P3 |
| P4 | Пакетный маршрут | P1 P2 D2 D1 | принято | P4 |
| P5 | Маршрут после забора | только вручение | принято | P5 |
| P6 | Жизненный цикл маршрутов | ACTIVE + PLANNED; ACTIVE → DONE; новый ACTIVE | принято, 3 маршрута | P6 |
| P7 | Перестановка точек в одной транзакции | обмен пар через свободные позиции | сразу — отказ триггера; с `SET CONSTRAINTS … DEFERRED` — принято | P7 |
| P8 | Зоны и пространственный индекс | квадрат вокруг точки, курьер в зоне | точка найдена `ST_Covers`, план запроса использует `ix_zones_area` | P8 |
| G-01 | Защита рабочих баз | пересоздать `delivery_db` | `ValueError`, сервер не трогается | `test_migration::test_scratch_name_guard` |

Чувствительность проверок проверена ослабленной копией миграции (без
триггера порядка, без CHECK окна, без частичного индекса, с каскадным
удалением вместо RESTRICT): провалились ровно N1, N2, N7, N8, N10 и P7 —
проверки ловят то, ради чего написаны.

### Сквозная проверка на живых процессах

| ID | Что проверяется | Входные данные | Ожидаемый результат | Где |
|---|---|---|---|---|
| E-01 | Весь путь заказа с Python-сервисом вместо TS | стенд `scripts/stand.sh up python`: catalog, order, payment, delivery, PostgreSQL/PostGIS, Kafka, Redis | 17 из 17 проверок пройдены (31.10.2026) | e2e `delivery-e2e.mjs` |
| E-02 | Цепочка предложений: молчание → следующему (DLV-4) | два курьера, первый молчит 5 с | заказ ушёл второму, цепочка в `delivery_assignments` | e2e |
| E-03 | Позицию видит только клиент заказа (SEC-7) | чужой клиент | 404 | e2e |
| E-04 | Задержка координаты до канала заказа (PERF-3 ≤ 2 с) | координаты курьера | медиана 7 мс, максимум 10 мс | e2e |
| E-05 | Компенсация: отмена оператором освобождает курьера | второй заказ отменён | курьер `available` | e2e |

## Покрытие кода

`pytest --cov=delivery`, 31.10.2026, без базы (тесты миграции
пропущены — они проверяют SQL, а не модули пакета):

| Модуль | Покрытие | Модуль | Покрытие |
|---|---|---|---|
| `lastmile/plan` | 100 % | `config` | 77 % |
| `context` | 100 % | `api` | 78 % |
| `lastmile/assign` | 98 % | `auth` | 72 % |
| `lastmile/graph` | 98 % | `routing` | 66 % |
| `errors` | 96 % | `clock` | 62 % |
| `lastmile/routes` | 95 % | `events` | 55 % |
| `log` | 93 % | `views` | 50 % |
| `lastmile/problem` | 91 % | `db` | 44 % |
| `domain` | 87 % | `dispatch` | 38 % |
| `lastmile/__main__` | 87 % | `store` | 32 % |
| `geo` | 79 % | `history` | 26 % |
| | | `positions` | 22 % |
| | | `tracking` | 19 % |
| | | `couriers` | 14 % |
| | | `outbox`, `background`, `__main__` | 0 % |
| **Всего** | **59 %** (1614 операторов, 666 не выполнены; до этапа — 51 %) | | |

Чтение: алгоритмы и предметная область покрыты почти целиком. Низкое
покрытие — у модулей, которые работают с PostgreSQL, Redis и Kafka
(`store`, `couriers`, `outbox`, `positions`, `history`, `tracking`,
`background`, запуск `__main__`): их проверяет сквозная проверка на живом
стенде (E-01…E-05), а не модульные тесты, поэтому в цифры pytest они не
попадают. Модульные тесты для них потребовали бы заглушек на каждый
SQL-запрос и проверяли бы заглушки, а не запросы.

## Качество кода

- **ruff** (`delivery-py/ruff.toml`, строка до 110 символов): pyflakes,
  pycodestyle, isort, bugbear, pep8-naming, pyupgrade, comprehensions,
  перехват голого `Exception` (только с пометкой и причиной), лишние
  пометки `noqa`. Те же правила — для `db/` (`db/ruff.toml`).
  Первый запуск — 10 замечаний: 4 длинные строки, 4 `timezone.utc` →
  `UTC`, 1 лишняя пометка `noqa: BLE001` (исключение там пробрасывается
  дальше, правило не срабатывает), 1 `zip()` без `strict=`. Исправлены без
  изменения поведения; `zip(..., strict=False)` оставлен осознанно: если
  маршрутизатор вернёт меньше строк, лишние кандидаты идут по прямой, а не
  роняют подбор. Сейчас — 0 замечаний.
- **Процедурный стиль** соблюдён: `class` нет ни в `delivery/`, ни в
  `tests/`, ни в `db/`.
- **Тесты**: 73 — 50 модульных и 23 на миграцию (21 ограничение, повторяемость, защита рабочих баз); до этапа — 41.

## Журнал найденных ошибок

| № | Где | Ошибка | Как найдена | Исправление | Тест |
|---|---|---|---|---|---|
| 1 | `lastmile/assign.py`, `_reason` | Без курьеров на смене заказ получал причину «перегруз: заказ больше вместимости любого курьера»: `all()` по пустому списку истинно. Оператор искал бы не ту проблему | сценарий «нет курьеров» при составлении таблицы сценариев | первой проверкой — «нет курьеров на смене» | A-18 |
| 2 | `dispatch.py`, `request_courier` | Индекс `ux_deliveries_order_active` (миграция 002) держит заказ и после вручения (`status <> 'CANCELLED'`), а поиск действующей доставки вручённую не видит (`NOT IN ('CANCELLED', 'DELIVERED')`). Повторный `RequestCourier` по такому заказу: поиск — пусто, вставка — дубль, повтор — и так по кругу: около тысячи вставок, затем `RecursionError` и ответ 500, который `order` повторял бы снова. В TS-оригинале то же самое (`dispatch.use-case.ts`), его тестовый двойник повторяет индекс и тоже зациклился бы | сверка условий индекса и запроса при написании миграции 007; отказ индекса подтверждён на базе (N13), зацикливание воспроизведено с заглушками — `RecursionError` | повтор после одновременного запроса — один раз; второй дубль — 409 «по заказу уже есть завершённая доставка» (`order` такую ошибку не повторяет, а передаёт оператору). Исходный проект не менялся | D-13, D-14, N13 |
| 3 | `lastmile/problem.py`, `validate` | Планировщик принимал окно нулевой длины `[30, 30]`, а физическая модель требует `window_start < window_end`: план содержал бы заказ, который нельзя сохранить | сверка правил планировщика с CHECK миграции 007 | правило `window[0] >= window[1]` — ошибка данных, как в базе. На тестовом наборе таких окон нет, результат плана прежний (18 из 20) | A-17, N1 |
