SET NAMES utf8mb4;

START TRANSACTION;

DELETE FROM user_balance_transactions
WHERE id BETWEEN 92230001 AND 92230136
  AND biz_no LIKE 'TEST-V101-BALANCE-%'
  AND remark LIKE 'TEST-V101%';

DELETE FROM user_balance_transactions
WHERE id BETWEEN 92231001 AND 92234156
  AND biz_no LIKE 'TEST-V101-%'
  AND remark LIKE 'TEST-V101%';

DELETE FROM product_monitor_states
WHERE channel_id BETWEEN 91150001 AND 91150012
  AND last_message LIKE 'TEST-V101 monitor state %';

DELETE FROM admin_staff
WHERE id BETWEEN 92210001 AND 92210004
  AND account LIKE 'test_v101_staff_%'
  AND nickname LIKE 'TEST-V101%';

DELETE FROM payment_channels
WHERE id BETWEEN 92220001 AND 92220004
  AND code LIKE 'test_v101_%_disabled'
  AND JSON_UNQUOTE(JSON_EXTRACT(config_public, '$.fixture')) = 'TEST-V101';

DELETE FROM price_templates
WHERE template_id LIKE 'test-v101-%'
  AND name LIKE 'TEST-V101%';

-- Singleton rows are removed only when this fixture created them. Existing
-- local settings never receive the marker and are therefore preserved.
DELETE FROM sms_login_settings
WHERE id = 1
  AND JSON_UNQUOTE(JSON_EXTRACT(generic_config_public, '$.fixture')) = 'TEST-V101'
  AND enabled = 0;

DELETE FROM captcha_settings
WHERE id = 1
  AND JSON_UNQUOTE(JSON_EXTRACT(generic_config_public, '$.fixture')) = 'TEST-V101'
  AND enabled = 0;

DELETE FROM cards
WHERE id BETWEEN 91180001 AND 91180600
  AND batch_no LIKE 'TEST-V101-BATCH-%'
  AND card_preview LIKE 'TEST-V101-%';

DELETE FROM open_api_logs
WHERE id BETWEEN 92100001 AND 92100156
  AND message LIKE 'TEST-V101%';

DELETE FROM member_api_credentials
WHERE id BETWEEN 92000001 AND 92000004
  AND app_key LIKE 'TEST-V101-APP-%';

DELETE FROM sms_logs
WHERE id BETWEEN 91900001 AND 91900036
  AND content LIKE 'TEST-V101%';

DELETE FROM admin_operation_logs
WHERE id BETWEEN 91800001 AND 91800060
  AND request_id LIKE 'TEST-V101-ADMIN-%';

DELETE FROM payment_callback_logs
WHERE id BETWEEN 91700001 AND 91700156
  AND order_no LIKE 'TEST-V101-%'
  AND JSON_UNQUOTE(JSON_EXTRACT(raw_payload, '$.fixture')) = 'TEST-V101';

DELETE FROM order_status_logs
WHERE (
    id BETWEEN 91600001 AND 91600156
    OR id BETWEEN 91610001 AND 91610156
  )
  AND order_no LIKE 'TEST-V101-%'
  AND remark LIKE 'TEST-V101%';

DELETE FROM delivery_tasks
WHERE id BETWEEN 91500001 AND 91500156
  AND task_no LIKE 'TEST-V101-TASK-%'
  AND order_no LIKE 'TEST-V101-%';

DELETE FROM refund_records
WHERE id BETWEEN 91400001 AND 91400156
  AND refund_no LIKE 'TEST-V101-REFUND-%'
  AND out_refund_no LIKE 'TEST-V101-OUT-REFUND-%';

DELETE FROM payment_records
WHERE id BETWEEN 91300001 AND 91300156
  AND payment_no LIKE 'TEST-V101-PAY-%'
  AND order_no LIKE 'TEST-V101-%';

DELETE FROM orders
WHERE id BETWEEN 91200001 AND 91200156
  AND order_no LIKE 'TEST-V101-%'
  AND request_id LIKE 'TEST-V101-REQUEST-%';

DELETE FROM goods_forbidden_platform
WHERE id BETWEEN 91190001 AND 91190180
  AND goods_id BETWEEN 91100001 AND 91100180
  AND reason = 'TEST-V101 平台禁售验证';

DELETE FROM goods_available_platform
WHERE id BETWEEN 91170001 AND 91170180
  AND goods_id BETWEEN 91100001 AND 91100180;

DELETE FROM goods_channels
WHERE id BETWEEN 91150001 AND 91150180
  AND goods_id BETWEEN 91100001 AND 91100180
  AND supplier_goods_id LIKE 'TEST-V101-UP-%';

DELETE FROM goods
WHERE id BETWEEN 91100001 AND 91100180
  AND name LIKE 'TEST-V101%'
  AND description LIKE 'TEST-V101%';

DELETE FROM card_kinds
WHERE id BETWEEN 91051001 AND 91051006
  AND name LIKE 'TEST-V101%';

DELETE FROM group_goods_rules
WHERE id BETWEEN 91060001 AND 91060008
  AND group_id BETWEEN 91030001 AND 91030004;

DELETE FROM recharge_fields
WHERE id BETWEEN 91050001 AND 91050002
  AND code LIKE 'test_v101_%'
  AND help_text LIKE 'TEST-V101%';

DELETE FROM suppliers
WHERE id BETWEEN 91040001 AND 91040004
  AND name LIKE 'TEST-V101%'
  AND remark = '仅允许回环地址，禁止真实外呼';

DELETE FROM system_settings
WHERE setting_key BETWEEN 'user.password.91031001' AND 'user.password.91031036'
  AND setting_value = '$2y$10$EreoWxRlsxEM5ESZMys5z.8yAl2cr111nqZoBG6sCGWz.sv6RM0fG';

DELETE FROM user_credentials
WHERE user_id BETWEEN 91031001 AND 91031036
  AND password_hash = '$2y$10$EreoWxRlsxEM5ESZMys5z.8yAl2cr111nqZoBG6sCGWz.sv6RM0fG';

DELETE FROM users
WHERE id BETWEEN 91031001 AND 91031036
  AND nickname LIKE 'TEST-V101%'
  AND email LIKE 'test-v101-user-%@example.invalid';

DELETE FROM user_groups
WHERE id BETWEEN 91030001 AND 91030006
  AND name LIKE 'TEST-V101%';

DELETE FROM categories
WHERE id BETWEEN 91001001 AND 91001040
  AND name LIKE 'TEST-V101%';

-- h5/web/api may predate this fixture. Remove only rows created with the
-- fixture-owned IDs and marker, never an existing platform resolved by code.
DELETE FROM sales_platforms
WHERE id BETWEEN 91000001 AND 91000004
  AND platform_code IN ('h5', 'web', 'api', 'pc')
  AND JSON_UNQUOTE(JSON_EXTRACT(config, '$.fixture')) = 'TEST-V101'
  AND NOT EXISTS (
    SELECT 1 FROM goods_available_platform r
    WHERE r.platform_id = sales_platforms.id
  )
  AND NOT EXISTS (
    SELECT 1 FROM goods_forbidden_platform r
    WHERE r.platform_id = sales_platforms.id
  )
  AND NOT EXISTS (
    SELECT 1 FROM orders o
    WHERE o.source_platform_id = sales_platforms.id
  );

COMMIT;
