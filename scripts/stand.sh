#!/usr/bin/env bash
# Стенд практики: инфраструктура в Docker (проект compose «fdlm», свои порты)
# и сервисы процессами на этой машине. catalog, order, payment и эмулятор
# платёжного провайдера запускаются из собранного исходного проекта как есть;
# вместо TS-сервиса delivery — его переписанная версия (Python или Java).
#
#   scripts/stand.sh up [python|java|ts]   — поднять стенд (по умолчанию python)
#   scripts/stand.sh e2e                   — сквозная проверка delivery-e2e.mjs проекта
#   scripts/stand.sh down                  — остановить процессы (контейнеры остаются)
#   scripts/stand.sh destroy               — остановить и удалить контейнеры и тома стенда
#
# Вход выключен (AUTH_DISABLED=true): проверка e2e ходит с фиктивными токенами
# и допускается только на localhost. Исходный проект не изменяется.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="${FOOD_DELIVERY_DIR:-$HERE/../food-delivery}"
STATE="$HERE/.stand"
mkdir -p "$STATE/logs"

export POSTGRES_PORT=25432 REDIS_PORT=26379 KAFKA_PORT=29092
export KAFKA_BROKERS=localhost:29092 AUTH_DISABLED=true NODE_ENV=development LOG_LEVEL=info
export OTEL_EXPORTER_OTLP_ENDPOINT=
CATALOG_PORT=13101 ORDER_PORT=13102 PAYMENT_PORT=13103 DELIVERY_PORT=13104 EMULATOR_PORT=13010

compose() {
  docker compose -p fdlm -f "$PROJECT/infra/compose/docker-compose.yml" \
    -f "$HERE/compose/fdlm.override.yml" --profile core "$@"
}

start() { # имя, команда…
  local name="$1"; shift
  ( "$@" >"$STATE/logs/$name.log" 2>&1 & echo $! >"$STATE/$name.pid" )
}

wait_http() { # имя, адрес
  for _ in $(seq 1 60); do
    if curl -sf "$2" >/dev/null 2>&1; then echo "  $1 готов"; return 0; fi
    sleep 1
  done
  echo "  $1 не ответил — см. $STATE/logs/$1.log"; return 1
}

up() {
  local impl="${1:-python}"
  compose up -d --wait
  (cd "$PROJECT" && node infra/migrations/migrate.mjs >/dev/null)

  start emulator env EMULATOR_PORT=$EMULATOR_PORT node "$PROJECT/apps/payment/dist/provider-emulator/main.js"
  start catalog env CATALOG_PORT=$CATALOG_PORT node "$PROJECT/apps/catalog/dist/main.js"
  start payment env PAYMENT_PORT=$PAYMENT_PORT PROVIDER_URL=http://localhost:$EMULATOR_PORT \
    ALLOW_DEV_SECRETS=true node "$PROJECT/apps/payment/dist/main.js"
  start order env ORDER_PORT=$ORDER_PORT CATALOG_URL=http://localhost:$CATALOG_PORT \
    PAYMENT_URL=http://localhost:$PAYMENT_PORT DELIVERY_URL=http://localhost:$DELIVERY_PORT \
    node "$PROJECT/apps/order/dist/main.js"

  # Короткий срок предложения и частая запись координат — как требует проверка e2e.
  local delivery_env=(DELIVERY_PORT=$DELIVERY_PORT OFFER_TTL_SECONDS=5 LOCATION_FLUSH_INTERVAL_MS=1000 OSRM_URL=)
  case "$impl" in
    python)
      local python="$HERE/delivery-py/.venv/Scripts/python"
      [ -x "$python" ] || python="$HERE/delivery-py/.venv/bin/python"
      start delivery env "${delivery_env[@]}" PYTHONUTF8=1 PYTHONPATH="$HERE/delivery-py" \
        "$python" -m delivery ;;
    java)
      # Самый большой jar в target — собранный со всеми зависимостями.
      start delivery env "${delivery_env[@]}" java -jar "$(ls -S "$HERE"/delivery-java/target/*.jar | head -1)" ;;
    ts)
      start delivery env "${delivery_env[@]}" node "$PROJECT/apps/delivery/dist/main.js" ;;
    *) echo "неизвестная реализация: $impl"; exit 2 ;;
  esac
  echo "$impl" >"$STATE/impl"

  wait_http emulator "http://localhost:$EMULATOR_PORT/health" || true
  wait_http catalog "http://localhost:$CATALOG_PORT/health"
  wait_http payment "http://localhost:$PAYMENT_PORT/health"
  wait_http order "http://localhost:$ORDER_PORT/health"
  wait_http delivery "http://localhost:$DELIVERY_PORT/health"
  echo "Стенд поднят, delivery: $impl"
}

down() {
  for pidfile in "$STATE"/*.pid; do
    [ -e "$pidfile" ] || continue
    kill "$(cat "$pidfile")" 2>/dev/null || true
    rm -f "$pidfile"
  done
  echo "Процессы стенда остановлены"
}

e2e() {
  (cd "$PROJECT" && ORDER_URL=http://localhost:$ORDER_PORT CATALOG_URL=http://localhost:$CATALOG_PORT \
    DELIVERY_URL=http://localhost:$DELIVERY_PORT node infra/verification/delivery-e2e.mjs)
}

case "${1:-}" in
  up) up "${2:-python}" ;;
  down) down ;;
  e2e) e2e ;;
  destroy) down; compose down -v ;;
  *) sed -n '2,15p' "$0"; exit 2 ;;
esac
