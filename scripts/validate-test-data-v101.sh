#!/usr/bin/env bash

set -euo pipefail

CONTAINER="xiyiyun-mysql"
DATABASE="xiyiyun"
EXPECTED_STATUSES="CANCELLED,CLOSED,CREATED,DELIVERED,DELIVERING,FAILED,PAID,PAYING,PROCURING,REFUNDED,REFUNDING,UNPAID,WAITING_MANUAL"
EXPECTED_TYPES="CARD,DIRECT,MANUAL"

fail() {
  echo "validate-test-data-v101: $1" >&2
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

query() {
  docker exec -i "$CONTAINER" sh -lc \
    'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 --batch --skip-column-names -u"$MYSQL_USER" "$MYSQL_DATABASE"' \
    <<<"$1"
}

expect_value() {
  local label sql expected actual
  label="$1"
  sql="$2"
  expected="$3"
  actual="$(query "$sql")"
  [[ "$actual" == "$expected" ]] || fail "$label: expected $expected, got ${actual:-<empty>}"
  echo "validate-test-data-v101: OK $label = $actual"
}

assert_local_mysql

expect_value "fixture category count" \
  "SELECT COUNT(*) FROM categories WHERE id BETWEEN 91001001 AND 91001040 AND name LIKE 'TEST-V101%' AND deleted_at IS NULL;" "40"
expect_value "five-level category branches" \
  "WITH RECURSIVE d AS (SELECT id, parent_id, 1 depth FROM categories WHERE id IN (91001001, 91001006, 91001011, 91001016, 91001021, 91001026, 91001031, 91001036) UNION ALL SELECT c.id, c.parent_id, d.depth + 1 FROM categories c JOIN d ON c.parent_id = d.id WHERE c.id BETWEEN 91001001 AND 91001040) SELECT CONCAT(MAX(depth), ':', SUM(depth = 5)) FROM d;" "5:8"
expect_value "fixture user group count" \
  "SELECT COUNT(*) FROM user_groups WHERE id BETWEEN 91030001 AND 91030006 AND name LIKE 'TEST-V101%' AND deleted_at IS NULL;" "6"
expect_value "fixture group rule count" \
  "SELECT COUNT(*) FROM group_goods_rules WHERE id BETWEEN 91060001 AND 91060008 AND group_id BETWEEN 91030001 AND 91030004 AND deleted_at IS NULL;" "8"
expect_value "fixture recharge field count" \
  "SELECT COUNT(*) FROM recharge_fields WHERE id BETWEEN 91050001 AND 91050002 AND code LIKE 'test_v101_%' AND deleted_at IS NULL;" "2"
expect_value "fixture card-kind count" \
  "SELECT COUNT(*) FROM card_kinds WHERE id BETWEEN 91051001 AND 91051006 AND name LIKE 'TEST-V101%' AND deleted_at IS NULL;" "6"
expect_value "fixture supplier count" \
  "SELECT COUNT(*) FROM suppliers WHERE id BETWEEN 91040001 AND 91040004 AND name LIKE 'TEST-V101%' AND deleted_at IS NULL;" "4"
expect_value "fixture monitor-capable supplier coverage" \
  "SELECT CONCAT(SUM(status = 'ENABLED'), ':', COUNT(DISTINCT platform_type)) FROM suppliers WHERE id BETWEEN 91040001 AND 91040004 AND platform_type IN ('FANCHEN','JINGZHAO','CHENGQUAN','KASUSHOU') AND base_url LIKE 'http://127.0.0.1:9/test-v101-%';" "3:4"
expect_value "fixture user count" \
  "SELECT COUNT(*) FROM users WHERE id BETWEEN 91031001 AND 91031036 AND nickname LIKE 'TEST-V101%' AND deleted_at IS NULL;" "36"
expect_value "fixture user passwords" \
  "SELECT COUNT(*) FROM system_settings WHERE setting_key BETWEEN 'user.password.91031001' AND 'user.password.91031036' AND LEFT(setting_value, 4) = CONCAT(CHAR(36), '2y', CHAR(36)) AND CHAR_LENGTH(setting_value) = 60;" "36"
expect_value "fixture extracted user credentials" \
  "SELECT COUNT(*) FROM user_credentials WHERE user_id BETWEEN 91031001 AND 91031036 AND LEFT(password_hash, 4) = CONCAT(CHAR(36), '2y', CHAR(36)) AND CHAR_LENGTH(password_hash) = 60;" "36"
expect_value "fixture admin staff" \
  "SELECT COUNT(*) FROM admin_staff WHERE id BETWEEN 92210001 AND 92210004 AND account LIKE 'test_v101_staff_%' AND nickname LIKE 'TEST-V101%' AND JSON_VALID(permissions) AND LEFT(password_hash, 4) = CONCAT(CHAR(36), '2y', CHAR(36));" "4"
expect_value "fixture admin staff status coverage" \
  "SELECT GROUP_CONCAT(CONCAT(status, ':', amount) ORDER BY status SEPARATOR ',') FROM (SELECT status, COUNT(*) amount FROM admin_staff WHERE id BETWEEN 92210001 AND 92210004 GROUP BY status) t;" \
  "DISABLED:1,ENABLED:3"
expect_value "fixture admin permission coverage" \
  "SELECT COUNT(DISTINCT p.permission) FROM admin_staff s JOIN JSON_TABLE(s.permissions, '\$[*]' COLUMNS(permission VARCHAR(64) PATH '\$')) p WHERE s.id BETWEEN 92210001 AND 92210004 AND p.permission IN ('dashboard:read','goods:manage','orders:manage','users:manage','settings:manage','staff:manage');" "6"
expect_value "fixture disabled payment placeholders" \
  "SELECT COUNT(*) FROM payment_channels WHERE id BETWEEN 92220001 AND 92220004 AND code LIKE 'test_v101_%_disabled' AND name LIKE 'TEST-V101%' AND status = 'DISABLED' AND JSON_UNQUOTE(JSON_EXTRACT(config_public, '$.fixture')) = 'TEST-V101' AND JSON_UNQUOTE(JSON_EXTRACT(config_public, '$.mock')) = 'true' AND config_secrets IS NULL;" "4"
expect_value "fixture payment channels cannot be selected" \
  "SELECT COUNT(*) FROM payment_channels WHERE id BETWEEN 92220001 AND 92220004 AND status = 'ENABLED';" "0"
expect_value "fixture price templates" \
  "SELECT COUNT(*) FROM price_templates WHERE template_id LIKE 'test-v101-%' AND name LIKE 'TEST-V101%' AND JSON_VALID(group_rates);" "3"
expect_value "fixture price-template state coverage" \
  "SELECT CONCAT(SUM(enabled = 1), ':', SUM(enabled = 0), ':', COUNT(DISTINCT UPPER(adjust_mode))) FROM price_templates WHERE template_id LIKE 'test-v101-%';" "2:1:2"
expect_value "fixture dynamic price-template bindings" \
  "SELECT GROUP_CONCAT(CONCAT(template_id, ':', amount) ORDER BY template_id SEPARATOR ',') FROM (SELECT JSON_UNQUOTE(JSON_EXTRACT(delivery_template, '$.priceTemplateId')) template_id, COUNT(*) amount FROM goods WHERE id BETWEEN 91100001 AND 91100180 AND JSON_UNQUOTE(JSON_EXTRACT(delivery_template, '$.priceMode')) = 'DYNAMIC' GROUP BY template_id) t;" \
  "test-v101-disabled:12,test-v101-fixed:12,test-v101-percent:12"
expect_value "SMS singleton preserved or safe fixture" \
  "SELECT COUNT(*) FROM sms_login_settings WHERE id = 1 AND (COALESCE(JSON_UNQUOTE(JSON_EXTRACT(generic_config_public, '$.fixture')), '') <> 'TEST-V101' OR (enabled = 0 AND admin_login_enabled = 0 AND h5_login_enabled = 0 AND web_login_enabled = 0 AND provider = 'MOCK' AND generic_config_secrets IS NULL AND tencent_config_secrets IS NULL AND aliyun_config_secrets IS NULL));" "1"
expect_value "SMS settings singleton cardinality" \
  "SELECT COUNT(*) FROM sms_login_settings;" "1"
expect_value "captcha singleton preserved or safe fixture" \
  "SELECT COUNT(*) FROM captcha_settings WHERE id = 1 AND (COALESCE(JSON_UNQUOTE(JSON_EXTRACT(generic_config_public, '$.fixture')), '') <> 'TEST-V101' OR (enabled = 0 AND admin_login_enabled = 0 AND h5_login_enabled = 0 AND web_login_enabled = 0 AND provider = 'ALTCHA' AND tencent_config_secrets IS NULL AND turnstile_config_secrets IS NULL AND generic_config_secrets IS NULL));" "1"
expect_value "captcha settings singleton cardinality" \
  "SELECT COUNT(*) FROM captcha_settings;" "1"
expect_value "fixture balance transactions" \
  "SELECT COUNT(*) FROM user_balance_transactions WHERE biz_no LIKE 'TEST-V101-%' AND remark LIKE 'TEST-V101%';" "288"
expect_value "fixture balance direction coverage" \
  "SELECT GROUP_CONCAT(CONCAT(direction, ':', amount) ORDER BY direction SEPARATOR ',') FROM (SELECT direction, COUNT(*) amount FROM user_balance_transactions WHERE biz_no LIKE 'TEST-V101-%' AND remark LIKE 'TEST-V101%' GROUP BY direction) t;" \
  "CREDIT:168,DEBIT:120"
expect_value "fixture balance business coverage" \
  "SELECT GROUP_CONCAT(CONCAT(biz_type, ':', amount) ORDER BY biz_type SEPARATOR ',') FROM (SELECT biz_type, COUNT(*) amount FROM user_balance_transactions WHERE biz_no LIKE 'TEST-V101-%' AND remark LIKE 'TEST-V101%' GROUP BY biz_type) t;" \
  "ADMIN_ADJUST:72,ORDER_PAY:84,ORDER_REFUND:12,PAYMENT_SETTLE:84,RECHARGE:36"
expect_value "fixture balance arithmetic errors" \
  "SELECT COUNT(*) FROM user_balance_transactions WHERE biz_no LIKE 'TEST-V101-%' AND remark LIKE 'TEST-V101%' AND (amount <= 0 OR (direction = 'CREDIT' AND balance_after <> balance_before + amount) OR (direction = 'DEBIT' AND balance_after <> balance_before - amount) OR direction NOT IN ('CREDIT', 'DEBIT'));" "0"
expect_value "fixture balance chain errors" \
  "SELECT COUNT(*) FROM users u LEFT JOIN user_balance_transactions c ON c.user_id = u.id AND c.biz_type = 'RECHARGE' AND c.biz_no LIKE 'TEST-V101-BALANCE-CREDIT-%' LEFT JOIN user_balance_transactions d ON d.user_id = u.id AND d.biz_type = 'ADMIN_ADJUST' AND d.biz_no LIKE 'TEST-V101-BALANCE-DEBIT-%' WHERE u.id BETWEEN 91031001 AND 91031036 AND (c.id IS NULL OR d.id IS NULL OR c.balance_before <> u.balance OR c.balance_after <> d.balance_before OR d.balance_after <> u.balance);" "0"
expect_value "fixture goods count" \
  "SELECT COUNT(*) FROM goods WHERE id BETWEEN 91100001 AND 91100180 AND name LIKE 'TEST-V101%' AND deleted_at IS NULL;" "180"
expect_value "all three goods types" \
  "SELECT GROUP_CONCAT(goods_type ORDER BY goods_type SEPARATOR ',') FROM (SELECT goods_type FROM goods WHERE id BETWEEN 91100001 AND 91100180 AND deleted_at IS NULL GROUP BY goods_type HAVING COUNT(*) = 60) t;" \
  "$EXPECTED_TYPES"
expect_value "fixture goods channel count" \
  "SELECT COUNT(*) FROM goods_channels WHERE id BETWEEN 91150001 AND 91150180 AND supplier_goods_id LIKE 'TEST-V101-UP-%' AND deleted_at IS NULL;" "180"
expect_value "fixture product monitor states" \
  "SELECT COUNT(*) FROM product_monitor_states WHERE channel_id BETWEEN 91150001 AND 91150012 AND last_message LIKE 'TEST-V101 monitor state %';" "12"
expect_value "fixture product monitor result coverage" \
  "SELECT GROUP_CONCAT(CONCAT(last_result, ':', amount) ORDER BY last_result SEPARATOR ',') FROM (SELECT last_result, COUNT(*) amount FROM product_monitor_states WHERE channel_id BETWEEN 91150001 AND 91150012 GROUP BY last_result) t;" \
  "CHANGED:3,FAILED:3,NO_CHANGE:3,WAITING:3"
expect_value "fixture available platform count" \
  "SELECT COUNT(*) FROM goods_available_platform WHERE id BETWEEN 91170001 AND 91170180 AND deleted_at IS NULL;" "180"
expect_value "fixture forbidden platform count" \
  "SELECT COUNT(*) FROM goods_forbidden_platform WHERE id BETWEEN 91190001 AND 91190180 AND reason = 'TEST-V101 平台禁售验证' AND deleted_at IS NULL;" "18"

expect_value "fixture order count" \
  "SELECT COUNT(*) FROM orders WHERE id BETWEEN 91200001 AND 91200156 AND order_no LIKE 'TEST-V101-%' AND request_id LIKE 'TEST-V101-REQUEST-%' AND deleted_at IS NULL;" "156"
expect_value "fixture upstream order numbers" \
  "SELECT CONCAT(COUNT(*), ':', COUNT(DISTINCT upstream_order_no)) FROM orders WHERE id BETWEEN 91200001 AND 91200156 AND upstream_order_no LIKE 'TEST-V101-UPSTREAM-%';" "20:20"
expect_value "fixture upstream order policy errors" \
  "SELECT COUNT(*) FROM orders WHERE id BETWEEN 91200001 AND 91200156 AND upstream_order_no IS NOT NULL AND (goods_type <> 'DIRECT' OR status NOT IN ('PROCURING', 'DELIVERED', 'FAILED', 'REFUNDING', 'REFUNDED') OR upstream_order_no NOT LIKE 'TEST-V101-UPSTREAM-%');" "0"
expect_value "all 13 order statuses" \
  "SELECT GROUP_CONCAT(status ORDER BY status SEPARATOR ',') FROM (SELECT status FROM orders WHERE id BETWEEN 91200001 AND 91200156 AND deleted_at IS NULL GROUP BY status HAVING COUNT(*) = 12) t;" \
  "$EXPECTED_STATUSES"
expect_value "twelve orders per status" \
  "SELECT COUNT(*) FROM (SELECT status FROM orders WHERE id BETWEEN 91200001 AND 91200156 AND deleted_at IS NULL GROUP BY status HAVING COUNT(*) = 12) t;" "13"
expect_value "fixture payment count" \
  "SELECT COUNT(*) FROM payment_records WHERE id BETWEEN 91300001 AND 91300156 AND payment_no LIKE 'TEST-V101-PAY-%';" "144"
expect_value "fixture callback count" \
  "SELECT COUNT(*) FROM payment_callback_logs WHERE id BETWEEN 91700001 AND 91700156 AND order_no LIKE 'TEST-V101-%';" "144"
expect_value "fixture callback idempotency keys" \
  "SELECT CONCAT(COUNT(*), ':', COUNT(DISTINCT idempotency_key)) FROM payment_callback_logs WHERE id BETWEEN 91700001 AND 91700156 AND idempotency_key LIKE 'TEST-V101-CALLBACK-IDEM-%';" "144:144"
expect_value "fixture refund count" \
  "SELECT COUNT(*) FROM refund_records WHERE id BETWEEN 91400001 AND 91400156 AND refund_no LIKE 'TEST-V101-REFUND-%';" "36"
expect_value "fixture delivery task count" \
  "SELECT COUNT(*) FROM delivery_tasks WHERE id BETWEEN 91500001 AND 91500156 AND task_no LIKE 'TEST-V101-TASK-%';" "72"
expect_value "fixture status-log count" \
  "SELECT COUNT(*) FROM order_status_logs WHERE ((id BETWEEN 91600001 AND 91600156) OR (id BETWEEN 91610001 AND 91610156)) AND order_no LIKE 'TEST-V101-%';" "300"
expect_value "fixture admin log count" \
  "SELECT COUNT(*) FROM admin_operation_logs WHERE id BETWEEN 91800001 AND 91800060 AND request_id LIKE 'TEST-V101-ADMIN-%';" "60"
expect_value "fixture SMS log count" \
  "SELECT COUNT(*) FROM sms_logs WHERE id BETWEEN 91900001 AND 91900036 AND content LIKE 'TEST-V101%';" "36"
expect_value "fixture SMS status coverage" \
  "SELECT GROUP_CONCAT(CONCAT(status, ':', amount) ORDER BY status SEPARATOR ',') FROM (SELECT status, COUNT(*) amount FROM sms_logs WHERE id BETWEEN 91900001 AND 91900036 GROUP BY status) t;" \
  "FAILED:12,SENT:12,SKIPPED:12"
expect_value "fixture SMS template coverage" \
  "SELECT COUNT(DISTINCT template_type) FROM sms_logs WHERE id BETWEEN 91900001 AND 91900036 AND template_type IN ('DELIVERED','FAILED','REFUNDED','ADMIN_LOGIN:admin','USER_LOGIN:h5','USER_LOGIN:web');" "6"
expect_value "fixture API credential count" \
  "SELECT COUNT(*) FROM member_api_credentials WHERE id BETWEEN 92000001 AND 92000004 AND app_key LIKE 'TEST-V101-APP-%';" "4"
expect_value "fixture open API log count" \
  "SELECT COUNT(*) FROM open_api_logs WHERE id BETWEEN 92100001 AND 92100156 AND message LIKE 'TEST-V101%';" "39"
expect_value "fixture goods cards" \
  "SELECT COUNT(*) FROM cards WHERE id BETWEEN 91180001 AND 91180600 AND batch_no LIKE 'TEST-V101-BATCH-%' AND deleted_at IS NULL;" "600"
expect_value "fixture card status distribution" \
  "SELECT GROUP_CONCAT(CONCAT(status, ':', amount) ORDER BY status SEPARATOR ',') FROM (SELECT status, COUNT(*) amount FROM cards WHERE id BETWEEN 91180001 AND 91180600 AND deleted_at IS NULL GROUP BY status) t;" \
  "LOCKED:60,SOLD:60,UNSOLD:480"
expect_value "fixture unique card cryptography fields" \
  "SELECT CONCAT(COUNT(DISTINCT card_hash), ':', COUNT(DISTINCT HEX(card_nonce)), ':', SUM(OCTET_LENGTH(card_nonce) = 12)) FROM cards WHERE id BETWEEN 91180001 AND 91180600 AND deleted_at IS NULL;" "600:600:600"

expect_value "terminal policy distribution" \
  "SELECT GROUP_CONCAT(CONCAT(platform_code, ':', amount) ORDER BY platform_code SEPARATOR ',') FROM (SELECT COALESCE(JSON_UNQUOTE(JSON_EXTRACT(delivery_template, '$.availablePlatforms[0]')), 'unrestricted') platform_code, COUNT(*) amount FROM goods WHERE id BETWEEN 91100001 AND 91100180 GROUP BY platform_code) t;" \
  "api:30,h5:60,pc:30,unrestricted:30,web:30"
expect_value "available platform distribution" \
  "SELECT GROUP_CONCAT(CONCAT(LOWER(p.platform_code), ':', amount) ORDER BY LOWER(p.platform_code) SEPARATOR ',') FROM (SELECT platform_id, COUNT(*) amount FROM goods_available_platform WHERE id BETWEEN 91170001 AND 91170180 AND deleted_at IS NULL GROUP BY platform_id) r JOIN sales_platforms p ON p.id = r.platform_id;" \
  "api:45,h5:45,pc:45,web:45"
expect_value "forbidden platform distribution" \
  "SELECT CONCAT(p.platform_code, ':', COUNT(*)) FROM goods_forbidden_platform r JOIN sales_platforms p ON p.id = r.platform_id WHERE r.id BETWEEN 91190001 AND 91190180 AND r.deleted_at IS NULL GROUP BY p.platform_code;" \
  "xianyu:18"
expect_value "order source distribution" \
  "SELECT GROUP_CONCAT(CONCAT(LOWER(source_platform_code), ':', amount) ORDER BY LOWER(source_platform_code) SEPARATOR ',') FROM (SELECT source_platform_code, COUNT(*) amount FROM orders WHERE id BETWEEN 91200001 AND 91200156 AND deleted_at IS NULL GROUP BY source_platform_code) t;" \
  "api:39,h5:39,pc:39,web:39"

expect_value "goods inventory boundaries" \
  "SELECT CONCAT(SUM(stock_count = 0), ':', SUM(stock_count = 1), ':', SUM(stock_count >= 999999)) FROM goods WHERE id BETWEEN 91100001 AND 91100180 AND deleted_at IS NULL;" "27:1:1"
expect_value "goods amount boundaries" \
  "SELECT CONCAT(SUM(sale_price = 0.0100), ':', SUM(sale_price = 999999.9999), ':', SUM(face_value IS NULL), ':', SUM(cost_price IS NULL)) FROM goods WHERE id BETWEEN 91100001 AND 91100180 AND deleted_at IS NULL;" "1:1:10:9"
expect_value "user verification coverage" \
  "SELECT GROUP_CONCAT(verification_status ORDER BY verification_status SEPARATOR ',') FROM (SELECT verification_status FROM users WHERE id BETWEEN 91031001 AND 91031036 GROUP BY verification_status) t;" \
  "PENDING,REJECTED,UNVERIFIED,VERIFIED"
expect_value "refund outcome coverage" \
  "SELECT GROUP_CONCAT(CONCAT(status, ':', amount) ORDER BY status SEPARATOR ',') FROM (SELECT status, COUNT(*) amount FROM refund_records WHERE id BETWEEN 91400001 AND 91400156 GROUP BY status) t;" \
  "FAILED:12,PROCESSING:12,SUCCESS:12"
expect_value "supplier outbound safety" \
  "SELECT COUNT(*) FROM suppliers WHERE id BETWEEN 91040001 AND 91040004 AND base_url LIKE 'http://127.0.0.1:9/test-v101-%';" "4"

expect_value "catalog association errors" \
  "SELECT (SELECT COUNT(*) FROM goods g LEFT JOIN categories c ON c.id = g.category_id AND c.deleted_at IS NULL LEFT JOIN card_kinds ck ON ck.id = JSON_UNQUOTE(JSON_EXTRACT(g.delivery_template, '$.cardKindId')) AND ck.deleted_at IS NULL WHERE g.id BETWEEN 91100001 AND 91100180 AND (c.id IS NULL OR (g.goods_type = 'CARD' AND ck.id IS NULL))) + (SELECT COUNT(*) FROM goods_channels gc LEFT JOIN goods g ON g.id = gc.goods_id AND g.deleted_at IS NULL LEFT JOIN suppliers s ON s.id = gc.supplier_id AND s.deleted_at IS NULL WHERE gc.id BETWEEN 91150001 AND 91150180 AND (g.id IS NULL OR s.id IS NULL));" "0"
expect_value "card association errors" \
  "SELECT COUNT(*) FROM cards c LEFT JOIN goods g ON g.id = c.goods_id AND g.goods_type = 'CARD' AND g.deleted_at IS NULL LEFT JOIN card_kinds ck ON ck.id = c.card_kind_id AND ck.deleted_at IS NULL LEFT JOIN orders lo ON lo.id = c.locked_order_id LEFT JOIN orders so ON so.id = c.sold_order_id WHERE c.id BETWEEN 91180001 AND 91180600 AND (g.id IS NULL OR ck.id IS NULL OR (c.status = 'LOCKED' AND (lo.id IS NULL OR lo.goods_id <> c.goods_id)) OR (c.status = 'SOLD' AND (so.id IS NULL OR so.goods_id <> c.goods_id)));" "0"
expect_value "platform association errors" \
  "SELECT (SELECT COUNT(*) FROM goods_available_platform r LEFT JOIN goods g ON g.id = r.goods_id AND g.deleted_at IS NULL LEFT JOIN sales_platforms p ON p.id = r.platform_id AND p.deleted_at IS NULL WHERE r.id BETWEEN 91170001 AND 91170180 AND (g.id IS NULL OR p.id IS NULL)) + (SELECT COUNT(*) FROM goods_forbidden_platform r LEFT JOIN goods g ON g.id = r.goods_id AND g.deleted_at IS NULL LEFT JOIN sales_platforms p ON p.id = r.platform_id AND p.deleted_at IS NULL WHERE r.id BETWEEN 91190001 AND 91190180 AND (g.id IS NULL OR p.id IS NULL));" "0"
expect_value "order association errors" \
  "SELECT COUNT(*) FROM orders o LEFT JOIN users u ON u.id = o.user_id AND u.deleted_at IS NULL LEFT JOIN goods g ON g.id = o.goods_id AND g.deleted_at IS NULL LEFT JOIN sales_platforms p ON p.id = o.source_platform_id AND p.deleted_at IS NULL WHERE o.id BETWEEN 91200001 AND 91200156 AND (u.id IS NULL OR g.id IS NULL OR p.id IS NULL OR o.goods_type <> g.goods_type OR o.source_platform_code <> p.platform_code);" "0"
expect_value "payment association errors" \
  "SELECT COUNT(*) FROM payment_records p LEFT JOIN orders o ON o.id = p.order_id AND o.order_no = p.order_no AND o.user_id = p.user_id AND o.deleted_at IS NULL WHERE p.id BETWEEN 91300001 AND 91300156 AND o.id IS NULL;" "0"
expect_value "callback association errors" \
  "SELECT COUNT(*) FROM payment_callback_logs c LEFT JOIN payment_records p ON p.payment_no = c.payment_no AND p.order_no = c.order_no WHERE c.id BETWEEN 91700001 AND 91700156 AND p.id IS NULL;" "0"
expect_value "refund association errors" \
  "SELECT COUNT(*) FROM refund_records r LEFT JOIN orders o ON o.id = r.order_id AND o.user_id = r.user_id AND o.deleted_at IS NULL LEFT JOIN payment_records p ON p.id = r.payment_id AND p.order_id = r.order_id WHERE r.id BETWEEN 91400001 AND 91400156 AND (o.id IS NULL OR p.id IS NULL);" "0"
expect_value "delivery association errors" \
  "SELECT COUNT(*) FROM delivery_tasks d LEFT JOIN orders o ON o.id = d.order_id AND o.order_no = d.order_no AND o.goods_id = d.goods_id AND o.deleted_at IS NULL LEFT JOIN suppliers s ON s.id = d.supplier_id AND s.deleted_at IS NULL WHERE d.id BETWEEN 91500001 AND 91500156 AND (o.id IS NULL OR s.id IS NULL);" "0"
expect_value "monitor-state association errors" \
  "SELECT COUNT(*) FROM product_monitor_states m LEFT JOIN goods_channels c ON c.id = m.channel_id AND c.deleted_at IS NULL WHERE m.channel_id BETWEEN 91150001 AND 91150012 AND (c.id IS NULL OR m.last_message NOT LIKE 'TEST-V101%');" "0"
expect_value "balance-ledger association errors" \
  "SELECT COUNT(*) FROM user_balance_transactions t LEFT JOIN users u ON u.id = t.user_id AND u.deleted_at IS NULL WHERE t.biz_no LIKE 'TEST-V101-%' AND t.remark LIKE 'TEST-V101%' AND (u.id IS NULL OR u.nickname NOT LIKE 'TEST-V101%');" "0"
expect_value "order-payment ledger association errors" \
  "SELECT COUNT(*) FROM user_balance_transactions t LEFT JOIN payment_records p ON p.order_no = t.biz_no AND p.user_id = t.user_id AND p.status = 'SUCCESS' WHERE t.biz_type = 'ORDER_PAY' AND t.remark LIKE 'TEST-V101%' AND p.id IS NULL;" "0"
expect_value "payment-settlement ledger association errors" \
  "SELECT COUNT(*) FROM user_balance_transactions t LEFT JOIN payment_records p ON p.payment_no = t.biz_no AND p.user_id = t.user_id AND p.status = 'SUCCESS' WHERE t.biz_type = 'PAYMENT_SETTLE' AND t.remark LIKE 'TEST-V101%' AND p.id IS NULL;" "0"
expect_value "order-refund ledger association errors" \
  "SELECT COUNT(*) FROM user_balance_transactions t LEFT JOIN orders o ON o.order_no = t.biz_no AND o.user_id = t.user_id AND o.status = 'REFUNDED' WHERE t.biz_type = 'ORDER_REFUND' AND t.remark LIKE 'TEST-V101%' AND o.id IS NULL;" "0"
expect_value "status-log association errors" \
  "SELECT COUNT(*) FROM order_status_logs l LEFT JOIN orders o ON o.id = l.order_id AND o.order_no = l.order_no AND o.deleted_at IS NULL WHERE ((l.id BETWEEN 91600001 AND 91600156) OR (l.id BETWEEN 91610001 AND 91610156)) AND o.id IS NULL;" "0"
expect_value "identity association errors" \
  "SELECT (SELECT COUNT(*) FROM users u LEFT JOIN user_groups g ON g.id = u.group_id AND g.deleted_at IS NULL WHERE u.id BETWEEN 91031001 AND 91031036 AND g.id IS NULL) + (SELECT COUNT(*) FROM member_api_credentials c LEFT JOIN users u ON u.id = c.user_id AND u.deleted_at IS NULL WHERE c.id BETWEEN 92000001 AND 92000004 AND u.id IS NULL) + (SELECT COUNT(*) FROM user_credentials c LEFT JOIN users u ON u.id = c.user_id AND u.deleted_at IS NULL WHERE c.user_id BETWEEN 91031001 AND 91031036 AND u.id IS NULL);" "0"

echo "validate-test-data-v101: all TEST-V101 checks passed"
