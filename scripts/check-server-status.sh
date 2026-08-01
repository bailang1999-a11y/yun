#!/usr/bin/env bash

set -uo pipefail

PROJECT_DIR="${PROJECT_DIR:-/vol1/1000/docker/xiyiyun}"
ENV_FILE="${ENV_FILE:-.env}"
FAILURES=0

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

ok() { echo -e "${GREEN}OK${NC} $1"; }
fail() { echo -e "${RED}FAIL${NC} $1"; FAILURES=$((FAILURES + 1)); }

if [[ ! -d "$PROJECT_DIR" ]]; then
  fail "project directory is missing: $PROJECT_DIR"
  exit 1
fi
cd "$PROJECT_DIR" || exit 1

if [[ ! -f "$ENV_FILE" ]]; then
  fail "environment file is missing: $ENV_FILE"
  exit 1
fi
if grep -Eq 'change_me|please_|example\.com' "$ENV_FILE"; then
  fail "$ENV_FILE contains placeholder values"
else
  ok "$ENV_FILE is present and has no obvious placeholders"
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

if ! command -v docker >/dev/null 2>&1; then
  fail "Docker is unavailable"
  exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
  fail "Docker Compose is unavailable"
  exit 1
fi

COMPOSE=(docker compose -p xiyiyun -f docker-compose.prod.yml --env-file "$ENV_FILE")
RUNNING="$("${COMPOSE[@]}" ps --status running --services 2>/dev/null || true)"
for service in mysql redis backend web h5 admin; do
  if grep -Fxq "$service" <<<"$RUNNING"; then
    ok "$service container is running"
  else
    fail "$service container is not running"
  fi
done

for port in "${WEB_PORT:-80}" "${H5_PORT:-18080}" "${ADMIN_PORT:-8088}"; do
  if { command -v ss >/dev/null 2>&1 && ss -ltn | grep -Eq ":${port}[[:space:]]"; } \
    || { command -v netstat >/dev/null 2>&1 && netstat -ltn | grep -Eq ":${port}[[:space:]]"; }; then
    ok "port $port is listening"
  else
    fail "port $port is not listening"
  fi
done

if curl --fail --silent --show-error --max-time 10 "http://127.0.0.1:${WEB_PORT:-80}/api/health" >/dev/null; then
  ok "backend readiness endpoint is healthy"
else
  fail "backend readiness endpoint is unhealthy"
fi

if "${COMPOSE[@]}" exec -T mysql sh -lc 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" -e "SELECT 1"' >/dev/null 2>&1; then
  ok "MySQL query succeeded"
else
  fail "MySQL query failed"
fi

if "${COMPOSE[@]}" exec -T redis redis-cli ping 2>/dev/null | grep -Fxq PONG; then
  ok "Redis ping succeeded"
else
  fail "Redis ping failed"
fi

"${COMPOSE[@]}" ps || true
"${COMPOSE[@]}" logs --tail=20 backend 2>/dev/null || true

if (( FAILURES > 0 )); then
  echo "$FAILURES deployment checks failed"
  exit 1
fi

echo "All deployment checks passed"
