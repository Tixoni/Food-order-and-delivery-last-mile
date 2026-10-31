-- «Последняя миля»: окна доставки, справочник транспорта, зоны курьеров,
-- маршрут как последовательность точек. Продолжает 006_delivery_routes.sql
-- исходного проекта (база delivery_db). Модель — docs/data-model.md.
--
-- Миграция повторяемая, как исходные: IF NOT EXISTS, проверка каталога
-- перед добавлением ограничений, CREATE OR REPLACE для функции. Повторный
-- запуск ничего не меняет (это проверяет db/check_migration.py).
--
-- Текущий сервис доставки (TS, Python, Java) новые колонки не пишет и не
-- читает: у новых колонок доставки есть значения по умолчанию или NULL,
-- поэтому его INSERT и UPDATE работают без изменений.

-- ─────────────── Справочник транспорта ───────────────
-- Скорость и вместимость — данные, а не константы в коде: их читает
-- планировщик «последней мили» (delivery/lastmile/problem.py, DEFAULT_VEHICLES).
CREATE TABLE IF NOT EXISTS vehicle_types (
    code      VARCHAR(16)  PRIMARY KEY,
    -- Средняя скорость в городе с остановками, км/ч
    speed_kmh NUMERIC(5, 2) NOT NULL CHECK (speed_kmh > 0),
    -- Сколько заказов (условных мест) курьер везёт одновременно
    capacity  SMALLINT     NOT NULL CHECK (capacity > 0)
);

-- Значения те же, что в коде сервиса (geo.SPEED_KMH) и планировщика.
-- DO NOTHING: повторный запуск не затирает значения, изменённые администратором.
INSERT INTO vehicle_types (code, speed_kmh, capacity) VALUES
    ('foot', 5, 2),
    ('bike', 14, 3),
    ('car', 20, 6)
ON CONFLICT (code) DO NOTHING;

-- Тип транспорта курьера — ссылка на справочник. RESTRICT: тип, на котором
-- работают курьеры, удалить нельзя, иначе у них пропадут скорость и вместимость.
--
-- CHECK (vehicle_type IN ('foot', 'bike', 'car')) из 001 остаётся, пока сервисы
-- держат скорости в коде: курьер с новым типом сломал бы расчёт времени в пути
-- (geo.SPEED_KMH[тип]). Когда сервис начнёт читать справочник, CHECK снимается
-- отдельной миграцией, и новый тип добавляется одной строкой в vehicle_types.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'fk_couriers_vehicle_type' AND conrelid = 'couriers'::regclass
    ) THEN
        ALTER TABLE couriers
            ADD CONSTRAINT fk_couriers_vehicle_type
            FOREIGN KEY (vehicle_type) REFERENCES vehicle_types (code)
            ON UPDATE CASCADE ON DELETE RESTRICT;
    END IF;
END $$;

-- ─────────────── Доставка: окно и размер ───────────────
-- Окно, в которое клиент ждёт заказ. У доставок, созданных текущим сервисом,
-- окна нет (NULL): ему окно не нужно, он назначает заказ сразу.
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS window_start TIMESTAMPTZ;
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS window_end   TIMESTAMPTZ;
-- Размер заказа в условных местах; обычный заказ — одно место.
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS size SMALLINT NOT NULL DEFAULT 1;

DO $$
BEGIN
    -- Пустой заказ (0 мест) планировщику не нужен: вместимость не расходуется,
    -- а маршрут удлиняется.
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'ck_deliveries_size' AND conrelid = 'deliveries'::regclass
    ) THEN
        ALTER TABLE deliveries ADD CONSTRAINT ck_deliveries_size CHECK (size > 0);
    END IF;
    -- Окно задаётся целиком или не задаётся: половина окна — ошибка данных.
    -- Отдельное ограничение, потому что сравнение с NULL в CHECK пропускает строку.
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'ck_deliveries_window_pair' AND conrelid = 'deliveries'::regclass
    ) THEN
        ALTER TABLE deliveries ADD CONSTRAINT ck_deliveries_window_pair
            CHECK ((window_start IS NULL) = (window_end IS NULL));
    END IF;
    -- Начало окна строго раньше конца: окно нулевой длины не выполнить.
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'ck_deliveries_window' AND conrelid = 'deliveries'::regclass
    ) THEN
        ALTER TABLE deliveries ADD CONSTRAINT ck_deliveries_window
            CHECK (window_start < window_end);
    END IF;
END $$;

-- Планировщик берёт ждущие заказы, самые срочные первыми (EDF, docs/algorithms.md):
-- частичный индекс по концу окна только для PENDING остаётся маленьким.
CREATE INDEX IF NOT EXISTS ix_deliveries_pending_window
    ON deliveries (window_end)
    WHERE status = 'PENDING' AND window_end IS NOT NULL;

-- ─────────────── Зоны работы курьеров ───────────────
-- Пеший курьер работает в пределах района; зона — полигон на сфере.
CREATE TABLE IF NOT EXISTS zones (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(100) NOT NULL UNIQUE,
    area       GEOGRAPHY(Polygon, 4326) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Самопересекающийся полигон даёт неверные ответы «точка в зоне».
    CONSTRAINT ck_zones_area_valid CHECK (ST_IsValid(area::geometry))
);

-- «В какой зоне точка» — ST_Covers(area, точка) прямо над geography, без
-- приведения к geometry, поэтому индекс строится по самой колонке. (В каталоге
-- исходного проекта запрос приводит колонку к geometry, и там нужен индекс по
-- выражению area::geometry — см. catalog/002_menu_and_zones.sql.)
-- Применимость индекса к запросу проверяет db/check_migration.py.
CREATE INDEX IF NOT EXISTS ix_zones_area
    ON zones USING GIST (area);

CREATE TABLE IF NOT EXISTS courier_zones (
    courier_id UUID NOT NULL REFERENCES couriers (id) ON DELETE CASCADE,
    zone_id    UUID NOT NULL REFERENCES zones (id) ON DELETE CASCADE,
    PRIMARY KEY (courier_id, zone_id)
);

-- Обратный поиск «кто работает в зоне»: первичный ключ начинается с курьера.
CREATE INDEX IF NOT EXISTS ix_courier_zones_zone
    ON courier_zones (zone_id);

-- ─────────────── Маршруты ───────────────
-- Маршрут курьера из нескольких заказов: план планировщика, затем исполнение.
CREATE TABLE IF NOT EXISTS routes (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    courier_id         UUID        NOT NULL REFERENCES couriers (id) ON DELETE CASCADE,
    -- PLANNED — посчитан, OFFERED — предложен курьеру, ACTIVE — курьер едет,
    -- DONE — все точки пройдены, CANCELLED — снят
    status             VARCHAR(16) NOT NULL DEFAULT 'PLANNED'
                       CHECK (status IN ('PLANNED', 'OFFERED', 'ACTIVE', 'DONE', 'CANCELLED')),
    planned_distance_m INTEGER     CHECK (planned_distance_m IS NULL OR planned_distance_m >= 0),
    planned_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Чем построен порядок точек: ближайший сосед или он же с улучшением 2-opt
    strategy           VARCHAR(16) NOT NULL DEFAULT 'two-opt'
                       CHECK (strategy IN ('greedy', 'two-opt'))
);

-- У курьера не больше одного маршрута в работе: иначе две очереди точек
-- спорят за одного человека. Запланированных и завершённых — сколько угодно.
CREATE UNIQUE INDEX IF NOT EXISTS ux_routes_courier_active
    ON routes (courier_id)
    WHERE status = 'ACTIVE';

-- История маршрутов курьера, новые первыми
CREATE INDEX IF NOT EXISTS ix_routes_courier_planned
    ON routes (courier_id, planned_at DESC);

-- ─────────────── Точки маршрута ───────────────
CREATE TABLE IF NOT EXISTS route_stops (
    route_id     UUID        NOT NULL REFERENCES routes (id) ON DELETE CASCADE,
    -- Порядок точки в маршруте, с единицы
    position     SMALLINT    NOT NULL CHECK (position > 0),
    delivery_id  UUID        NOT NULL REFERENCES deliveries (id) ON DELETE CASCADE,
    -- PICKUP — забрать в ресторане, DROPOFF — вручить клиенту
    kind         VARCHAR(8)  NOT NULL CHECK (kind IN ('PICKUP', 'DROPOFF')),
    planned_at   TIMESTAMPTZ,
    arrived_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (route_id, position),
    -- Заказ забирается и вручается в маршруте по одному разу
    CONSTRAINT ux_route_stops_delivery_kind UNIQUE (route_id, delivery_id, kind),
    -- Точку нельзя закрыть раньше, чем курьер на неё прибыл
    CONSTRAINT ck_route_stops_completed_after_arrival
        CHECK (completed_at IS NULL OR (arrived_at IS NOT NULL AND completed_at >= arrived_at))
);

-- «В каком маршруте этот заказ» и проверка внешнего ключа при удалении доставки
CREATE INDEX IF NOT EXISTS ix_route_stops_delivery
    ON route_stops (delivery_id);

-- Забор раньше вручения. Ограничение затрагивает две строки, поэтому CHECK
-- не подходит — нужен триггер. Проверяется, только когда у заказа в маршруте
-- есть обе точки: маршрут, перестроенный после забора, содержит одно вручение
-- (заказ уже на руках), и это допустимо.
CREATE OR REPLACE FUNCTION route_stops_pickup_before_dropoff() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    pickup_position  SMALLINT;
    dropoff_position SMALLINT;
BEGIN
    SELECT max(position) FILTER (WHERE kind = 'PICKUP'),
           max(position) FILTER (WHERE kind = 'DROPOFF')
      INTO pickup_position, dropoff_position
      FROM route_stops
     WHERE route_id = NEW.route_id AND delivery_id = NEW.delivery_id;

    IF pickup_position IS NOT NULL AND dropoff_position IS NOT NULL
       AND pickup_position > dropoff_position THEN
        RAISE EXCEPTION USING
            -- Тот же код, что у нарушения CHECK: сервис обрабатывает их одинаково
            ERRCODE    = 'check_violation',
            CONSTRAINT = 'trg_route_stops_pickup_before_dropoff',
            MESSAGE    = format(
                'маршрут %s: вручение заказа %s (точка %s) раньше забора (точка %s)',
                NEW.route_id, NEW.delivery_id, dropoff_position, pickup_position);
    END IF;
    RETURN NULL;
END $$;

-- Триггер-ограничение: по умолчанию проверка сразу после команды, но
-- перестановка точек в одной транзакции может отложить её до фиксации
-- (SET CONSTRAINTS trg_route_stops_pickup_before_dropoff DEFERRED) —
-- промежуточный порядок при обмене позициями бывает недопустимым.
-- OR REPLACE для триггера-ограничения PostgreSQL не поддерживает,
-- поэтому повторный запуск пересоздаёт его.
DROP TRIGGER IF EXISTS trg_route_stops_pickup_before_dropoff ON route_stops;
CREATE CONSTRAINT TRIGGER trg_route_stops_pickup_before_dropoff
    AFTER INSERT OR UPDATE OF route_id, position, delivery_id, kind ON route_stops
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION route_stops_pickup_before_dropoff();

-- ─────────────── Описание в словаре данных СУБД ───────────────
COMMENT ON TABLE vehicle_types IS 'Справочник транспорта: скорость и вместимость курьера';
COMMENT ON TABLE zones IS 'Зоны работы курьеров (полигоны)';
COMMENT ON TABLE courier_zones IS 'В каких зонах работает курьер';
COMMENT ON TABLE routes IS 'Маршрут курьера из нескольких заказов';
COMMENT ON TABLE route_stops IS 'Точки маршрута: забор и вручение заказов по порядку';
COMMENT ON COLUMN deliveries.window_start IS 'Начало окна доставки';
COMMENT ON COLUMN deliveries.window_end IS 'Конец окна доставки, строго позже начала';
COMMENT ON COLUMN deliveries.size IS 'Размер заказа в условных местах';
