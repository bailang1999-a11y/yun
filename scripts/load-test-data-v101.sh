#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER="xiyiyun-mysql"
BACKEND_CONTAINER="xiyiyun-backend-dev-8081"
DATABASE="xiyiyun"
FIXTURE_SQL="$ROOT_DIR/db/fixtures/TEST_V101.sql"
CARD_TSV="$ROOT_DIR/backend/target/test-v101/cards.tsv"
VALIDATE_SCRIPT="$ROOT_DIR/scripts/validate-test-data-v101.sh"

fail() {
  echo "load-test-data-v101: $1" >&2
  exit 1
}

assert_local_mysql() {
  command -v docker >/dev/null 2>&1 || fail "Docker is unavailable"

  if [[ -n "${DOCKER_HOST:-}" && "${DOCKER_HOST}" != unix://* ]]; then
    fail "refusing non-local DOCKER_HOST: ${DOCKER_HOST%%:*}"
  fi

  local context endpoint actual_name project service running database
  context="$(docker context show)"
  endpoint="$(docker context inspect "$context" --format '{{.Endpoints.docker.Host}}')"
  [[ "$endpoint" == unix://* ]] || fail "refusing non-local Docker endpoint"

  actual_name="$(docker inspect --type container --format '{{.Name}}' "$CONTAINER" 2>/dev/null)" \
    || fail "container $CONTAINER does not exist"
  [[ "$actual_name" == "/$CONTAINER" ]] || fail "unexpected container name: $actual_name"

  project="$(docker inspect --type container --format '{{index .Config.Labels "com.docker.compose.project"}}' "$CONTAINER")"
  service="$(docker inspect --type container --format '{{index .Config.Labels "com.docker.compose.service"}}' "$CONTAINER")"
  [[ "$project" == "xiyiyun" && "$service" == "mysql" ]] \
    || fail "container is not the local xiyiyun/mysql Compose service"

  running="$(docker inspect --type container --format '{{.State.Running}}' "$CONTAINER")"
  [[ "$running" == "true" ]] || fail "container $CONTAINER is not running"

  database="$(docker exec "$CONTAINER" sh -lc 'printf %s "$MYSQL_DATABASE"')"
  [[ "$database" == "$DATABASE" ]] || fail "container database is not $DATABASE"
}

[[ -f "$FIXTURE_SQL" ]] || fail "fixture SQL is missing: $FIXTURE_SQL"
[[ -x "$VALIDATE_SCRIPT" ]] || fail "validation script is not executable: $VALIDATE_SCRIPT"

assert_local_mysql

for migration in \
  "$ROOT_DIR/db/migrations/002_config_persistence.sql" \
  "$ROOT_DIR/db/migrations/003_category_icons.sql" \
  "$ROOT_DIR/db/migrations/004_security_and_order_consistency.sql" \
  "$ROOT_DIR/db/migrations/005_cards_kind_and_callback_logs.sql" \
  "$ROOT_DIR/db/migrations/006_money_integrity.sql" \
  "$ROOT_DIR/db/migrations/007_config_tables_extraction.sql"
do
  docker exec -i "$CONTAINER" sh -lc \
    'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE"' \
    < "$migration"
done

docker exec -i "$CONTAINER" sh -lc \
  'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE"' \
  < "$FIXTURE_SQL"

[[ "$(docker inspect --type container --format '{{.State.Running}}' "$BACKEND_CONTAINER" 2>/dev/null)" == "true" ]] \
  || fail "container $BACKEND_CONTAINER is not running"
docker exec "$BACKEND_CONTAINER" sh -lc '
  test -n "$XIYIYUN_CARD_ENCRYPTION_SECRET"
  mvn -q -DskipTests test-compile \
    org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
    -Dexec.mainClass=com.xiyiyun.shop.persistence.fixture.TestCardFixtureGenerator \
    -Dexec.classpathScope=test \
    -Dexec.args=/workspace/target/test-v101/cards.tsv
'
[[ -f "$CARD_TSV" ]] || fail "encrypted card fixture was not generated"

awk -F '\t' '
  NR == 1 { next }
  {
    locked = $11 == "\\N" ? "NULL" : $11
    sold = $12 == "\\N" ? "NULL" : $12
    sold_at = $13 == "\\N" ? "NULL" : sprintf("\047%s\047", $13)
    printf "INSERT IGNORE INTO cards (id, goods_id, card_kind_id, batch_no, card_ciphertext, card_nonce, card_key_version, card_hash, card_preview, status, locked_order_id, sold_order_id, sold_at, created_at) VALUES (%d, %s, %s, \047%s\047, UNHEX(\047%s\047), UNHEX(\047%s\047), \047%s\047, \047%s\047, \047%s\047, \047%s\047, %s, %s, %s, \047%s\047);\n", 91180000 + $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, locked, sold, sold_at, $14
  }
' "$CARD_TSV" | docker exec -i "$CONTAINER" sh -lc \
  'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE"'

docker exec -i "$CONTAINER" sh -lc \
  'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE"' <<'SQL'
UPDATE goods g
SET stock_count = (
  SELECT COUNT(*) FROM cards c WHERE c.goods_id = g.id AND c.status = 'UNSOLD' AND c.deleted_at IS NULL
)
WHERE g.id BETWEEN 91100001 AND 91100180
  AND g.goods_type = 'CARD'
  AND g.name LIKE 'TEST-V101%';

UPDATE orders o
JOIN (
  SELECT sold_order_id, JSON_ARRAYAGG(id) AS card_ids
  FROM cards
  WHERE id BETWEEN 91180001 AND 91180600 AND sold_order_id IS NOT NULL
  GROUP BY sold_order_id
) c ON c.sold_order_id = o.id
SET o.delivery_card_ids_json = c.card_ids
WHERE o.id BETWEEN 91200001 AND 91200156 AND o.order_no LIKE 'TEST-V101-%';
SQL

"$VALIDATE_SCRIPT"
echo "load-test-data-v101: TEST-V101 fixture loaded and validated"
