# TEST-V101 local fixture

This fixture is for the local `xiyiyun` MySQL database only. It preserves existing rows, uses fixed high-range IDs, and marks business data with `TEST-V101`.

## Coverage

- 40 category nodes: eight complete five-level branches.
- 180 goods: 60 each of `CARD`, `DIRECT`, and `MANUAL`.
- 36 users across six groups, including order-disabled, group-disabled, real-name, balance, deposit, Unicode, and status boundaries.
- 600 AES-GCM encrypted cards: 480 `UNSOLD`, 60 `LOCKED`, and 60 `SOLD`.
- 156 orders: 12 rows for each of the 13 order statuses and all goods/platform combinations.
- 20 unique upstream order numbers on eligible `DIRECT` orders.
- 288 balance-ledger rows covering `RECHARGE`, `ADMIN_ADJUST`, `ORDER_PAY`, `PAYMENT_SETTLE`, and `ORDER_REFUND` with both directions and linked business numbers.
- Payments, callbacks, refunds, delivery tasks, status logs, admin logs, SMS logs, member API credentials, and open API logs.
- Extracted v1.20 configuration data: 36 `user_credentials`, four admin staff accounts covering all six permissions, four disabled payment placeholders, three linked price templates, and 12 product-monitor states.
- Safe singleton settings: disabled `MOCK` SMS and disabled `ALTCHA` captcha rows are inserted only when `id=1` does not already exist.
- H5, Web, PC, API, unrestricted, allowed, and forbidden platform policies.
- Zero, low, and high stock; minimum and high amounts; null values; long text; and Unicode.

Fixture suppliers use monitor-capable platform types but only `127.0.0.1:9`, so monitor lists have data without reaching a real upstream service.
All fixture payment channels are `DISABLED`, contain no secret envelope, and cannot be selected for payment.

## Commands

```bash
./scripts/load-test-data-v101.sh
./scripts/validate-test-data-v101.sh
```

Cleanup is intentionally separate:

```bash
docker exec -i xiyiyun-mysql sh -lc \
  'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE"' \
  < db/fixtures/cleanup_TEST_V101.sql
```

## Test users

- Mobile range: `19991000001` through `19991000036`.
- Shared local-only password: `Test@123456`.
- Admin staff accounts: `test_v101_staff_01` through `test_v101_staff_04`, with the same local-only password; account 04 is disabled.
- Example normal user: `19991000001`.
- Example delivered card order owner: `19991000034`, order `TEST-V101-0034`.

The fixture preserves an existing captcha setting. If captcha is enabled, test logins must complete the configured provider; local operators can disable it separately for an isolated test environment.

## Safety

- The loader accepts only the local Unix Docker endpoint, the `xiyiyun-mysql` container, Compose project `xiyiyun`, service `mysql`, and database `xiyiyun`.
- It applies existing migrations `002` through `007` in order, then loads and validates the fixture.
- Re-running the loader is idempotent and does not increase fixture counts.
- Re-running over an older TEST-V101 load only fills missing `upstream_order_no` and callback `idempotency_key` values on fixture-owned rows; non-null values are preserved.
- The cleanup script requires both fixture ID ranges and `TEST-V101` markers before deleting rows.
- Existing SMS/captcha singleton settings are never overwritten or deleted. Fixture singleton rows are removed only when their JSON config still contains the `TEST-V101` marker.
