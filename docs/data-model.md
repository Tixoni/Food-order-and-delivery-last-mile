# Модель данных доставки

## Логическая модель «как есть» — `delivery_db`

Схема сервиса доставки исходного проекта (миграции
`food-delivery/infra/migrations/delivery/001–006`). Python- и Java-версии
сервиса работают с ней без изменений.

```mermaid
erDiagram
    COURIERS ||--o{ COURIER_SHIFTS : "работает сменами"
    COURIERS ||--o{ COURIER_LOCATIONS : "оставляет след"
    COURIERS ||--o{ DELIVERY_ASSIGNMENTS : "получает предложения"
    COURIERS |o--o| DELIVERIES : "везёт сейчас"
    DELIVERIES ||--o{ DELIVERY_ASSIGNMENTS : "предлагается по очереди"
    DELIVERIES ||--o| DELIVERY_ROUTES : "маршрут ресторан → клиент"

    COURIERS {
        uuid id PK
        uuid user_id UK "учётная запись Keycloak"
        varchar full_name
        varchar vehicle_type "foot | bike | car"
        varchar status "offline | available | assigned | busy"
        uuid current_delivery_id FK
        geography last_location "GiST"
        timestamptz last_location_at
        boolean is_active
    }
    COURIER_SHIFTS {
        uuid id PK
        uuid courier_id FK "одна открытая смена"
        timestamptz started_at
        timestamptz ended_at
    }
    COURIER_LOCATIONS {
        bigserial id PK
        uuid courier_id FK
        geography location
        numeric speed
        numeric accuracy
        timestamptz recorded_at
    }
    DELIVERIES {
        uuid id PK
        uuid order_id "один активный на заказ"
        uuid courier_id FK
        geography pickup_location
        geography dropoff_location
        varchar status "PENDING | ASSIGNED | PICKED_UP | DELIVERED | CANCELLED"
        timestamptz ready_at
        varchar request_key "идемпотентность запроса от order"
        timestamptz search_started_at "раунд поиска"
        int version
    }
    DELIVERY_ASSIGNMENTS {
        uuid id PK
        uuid delivery_id FK
        uuid courier_id FK
        smallint sequence "номер попытки"
        timestamptz expires_at "срок отклика 90 с"
        varchar response "ACCEPTED | REJECTED | TIMEOUT | CANCELLED"
    }
    DELIVERY_ROUTES {
        uuid delivery_id PK
        int distance_m
        geography path
    }
```

Плюс общие для всех сервисов `outbox_messages` (события в той же
транзакции, что и изменение данных) и `inbox_messages` (защита от повторов).

## Логическая модель «как будет» — расширение под «последнюю милю»

Чего не хватает для пакетной доставки с окнами (выделено в диаграмме
новыми сущностями и полями):

- **окно доставки** у заказа — `window_start`, `window_end`;
- **вместимость** — справочник транспорта вместо констант в коде;
- **зоны** — где курьер работает (пеший — в пределах района);
- **маршрут как последовательность точек** — несколько заказов у одного
  курьера, плановое и фактическое время на каждой точке.

```mermaid
erDiagram
    VEHICLE_TYPES ||--o{ COURIERS : "транспорт"
    ZONES ||--o{ COURIER_ZONES : ""
    COURIERS ||--o{ COURIER_ZONES : "работает в зонах"
    COURIERS ||--o{ ROUTES : "получает маршрут"
    ROUTES ||--|{ ROUTE_STOPS : "состоит из точек"
    DELIVERIES ||--|{ ROUTE_STOPS : "забор и вручение"

    VEHICLE_TYPES {
        varchar code PK "foot | bike | car"
        numeric speed_kmh
        smallint capacity "мест одновременно"
    }
    ZONES {
        uuid id PK
        varchar name
        geography area "полигон, GiST"
    }
    COURIER_ZONES {
        uuid courier_id PK, FK
        uuid zone_id PK, FK
    }
    DELIVERIES {
        uuid id PK
        timestamptz window_start "новое"
        timestamptz window_end "новое, CHECK start < end"
        smallint size "новое, мест"
    }
    ROUTES {
        uuid id PK
        uuid courier_id FK
        varchar status "PLANNED | OFFERED | ACTIVE | DONE | CANCELLED"
        int planned_distance_m
        timestamptz planned_at
        varchar strategy "greedy | two-opt"
    }
    ROUTE_STOPS {
        uuid route_id PK, FK
        smallint position PK "порядок в маршруте"
        uuid delivery_id FK
        varchar kind "PICKUP | DROPOFF"
        timestamptz planned_at
        timestamptz arrived_at
        timestamptz completed_at
    }
```

Ограничения целостности, которые нужны в физической модели (этап 5):

- `UNIQUE (route_id, delivery_id, kind)` — заказ забирается и вручается в
  маршруте по одному разу;
- забор раньше вручения — проверяется триггером или в сервисе
  (`position` забора < `position` вручения);
- `CHECK (window_start < window_end)`, `CHECK (size > 0)`;
- частичный индекс активных маршрутов курьера:
  `CREATE UNIQUE INDEX … ON routes (courier_id) WHERE status = 'ACTIVE'`.

Физическая модель — `db/migrations/007_last_mile.sql` (все перечисленные
ограничения; забор раньше вручения — триггером-ограничением), проверка —
`db/check_migration.py`, сценарии — `docs/testing.md`.
