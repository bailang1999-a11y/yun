package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.it.support.ItDatabase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任务B：结构叠加校验。
 *
 * <p>分工说明：「全新库路径（001+迁移）」与「老库升级路径」产出结构完全一致这件事，
 * {@code scripts/schema-check.mjs} 已经用两个临时库（schema_check_fresh / schema_check_upgraded，
 * 后者以 git ref 37c7fa9 的历史版 001 为老库基线）做了双向 information_schema 差异比对，
 * Java 侧不重复造轮子。
 *
 * <p>本测试守住 Java 能守的那一半：mapper 注解 SQL 依赖的结构在测试库里**真的存在且类型正确**。
 * 若哪次迁移漏跑或被改坏，这里会先红，而不是等某个并发用例报出莫名其妙的 SQL 错误。
 */
class SchemaStackingTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("classpath 上的建库脚本副本与 CHECKSUMS.txt 一致（未被篡改）")
    void schemaScriptCopiesMatchChecksums() {
        String manifest = ItDatabase.readResource("db/CHECKSUMS.txt");
        Map<String, String> expected = manifest.lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty())
            .map(line -> line.split("\\s+", 2))
            .collect(java.util.stream.Collectors.toMap(
                parts -> parts[1].strip(), parts -> parts[0].strip()));

        assertThat(expected).hasSize(ItDatabase.schemaScriptResources().size());
        for (String resource : ItDatabase.schemaScriptResources()) {
            String key = "db/" + resource.substring("db/".length());
            String content = ItDatabase.readResource(resource);
            assertThat(sha256Hex(content))
                .as("%s 与 CHECKSUMS.txt 记录的源文件摘要不一致，classpath 副本已漂移", resource)
                .isEqualTo(expected.get(key));
        }
    }

    @Test
    @DisplayName("会员回调地址与加密订单状态回调任务结构存在")
    void memberOrderCallbackStructureExists() {
        assertColumn("member_api_credentials", "callback_url", "varchar(500)", true);
        assertColumn("orders", "buyer_account", "varchar(255)", true);
        assertTableExists("member_order_callback_tasks");
        assertColumn("member_order_callback_tasks", "event_id", "varchar(64)", false);
        assertColumn("member_order_callback_tasks", "payload_json", "json", false);
        assertColumn("member_order_callback_tasks", "sensitive_ciphertext", "longblob", false);
        assertColumn("member_order_callback_tasks", "sensitive_nonce", "varbinary(12)", false);
        assertColumn("member_order_callback_tasks", "sensitive_key_version", "varchar(32)", false);
        assertColumn("member_order_callback_tasks", "state", "varchar(16)", false);
        assertUniqueIndexColumns(
            "uk_member_order_callback_event",
            "member_order_callback_tasks",
            List.of("event_id")
        );
    }

    @Test
    @DisplayName("阿奇索业务拒绝记录具有幂等键和管理查询字段")
    void agisoRejectedOrderStructureExists() {
        assertTableExists("agiso_rejected_orders");
        assertColumn("agiso_rejected_orders", "external_max_amount", "decimal(18,4)", true);
        assertColumn("agiso_rejected_orders", "expected_amount", "decimal(18,4)", true);
        assertColumn("agiso_rejected_orders", "reject_reason", "varchar(1000)", false);
        assertUniqueIndexColumns(
            "uk_agiso_rejected_order",
            "agiso_rejected_orders",
            List.of("protocol", "user_id", "external_order_no")
        );
    }

    @Test
    @DisplayName("企业微信群机器人可靠通知任务结构存在")
    void weComRobotDeliveryTaskStructureExists() {
        assertTableExists("wecom_robot_delivery_tasks");
        assertColumn("wecom_robot_delivery_tasks", "event_id", "varchar(64)", false);
        assertColumn("wecom_robot_delivery_tasks", "event_type", "varchar(32)", false);
        assertColumn("wecom_robot_delivery_tasks", "markdown_content", "text", false);
        assertColumn("wecom_robot_delivery_tasks", "state", "varchar(16)", false);
        assertUniqueIndexColumns(
            "uk_wecom_robot_delivery_event",
            "wecom_robot_delivery_tasks",
            List.of("event_id")
        );
    }

    @Test
    @DisplayName("cards.card_kind_id 与 uk_cards_kind_hash 存在（005/001 卡种维度）")
    void cardsCardKindStructureExists() {
        assertColumn("cards", "card_kind_id", "bigint unsigned", true);
        assertUniqueIndexColumns("uk_cards_kind_hash", "cards", List.of("card_kind_id", "card_hash"));
        assertColumn("cards", "card_hash", "char(64)", false);
        assertColumn("cards", "sold_order_id", "bigint unsigned", true);
        assertColumn("cards", "status", "varchar(32)", false);
    }

    @Test
    @DisplayName("payment_callback_logs 表 + 006 的 idempotency_key 唯一键存在")
    void paymentCallbackLogStructureExists() {
        assertTableExists("payment_callback_logs");
        assertColumn("payment_callback_logs", "idempotency_key", "varchar(128)", true);
        assertUniqueIndexColumns("uk_payment_callback_idem", "payment_callback_logs", List.of("idempotency_key"));
        assertColumn("payment_callback_logs", "result", "varchar(32)", false);
    }

    @Test
    @DisplayName("user_balance_transactions 表结构与幂等唯一键 uk_balance_tx_biz 存在")
    void balanceTransactionStructureExists() {
        assertTableExists("user_balance_transactions");
        assertColumn("user_balance_transactions", "user_id", "bigint unsigned", false);
        assertColumn("user_balance_transactions", "direction", "varchar(16)", false);
        assertColumn("user_balance_transactions", "amount", "decimal(18,4)", false);
        assertColumn("user_balance_transactions", "balance_before", "decimal(18,4)", false);
        assertColumn("user_balance_transactions", "balance_after", "decimal(18,4)", false);
        assertColumn("user_balance_transactions", "biz_type", "varchar(32)", false);
        assertColumn("user_balance_transactions", "biz_no", "varchar(64)", false);
        assertUniqueIndexColumns("uk_balance_tx_biz", "user_balance_transactions", List.of("biz_type", "biz_no"));
    }

    @Test
    @DisplayName("orders.upstream_order_no + uk_orders_upstream 存在（006 防重复采购）")
    void upstreamOrderNoStructureExists() {
        assertColumn("orders", "upstream_order_no", "varchar(64)", true);
        assertUniqueIndexColumns("uk_orders_upstream", "orders", List.of("upstream_order_no"));
    }

    @Test
    @DisplayName("阿奇索价格订阅租约带所有权令牌")
    void agisoPriceSubscriptionLeaseTokenExists() {
        assertColumn("agiso_price_subscriptions", "lease_token", "varchar(64)", true);
        assertUniqueIndexColumns(
            "uk_agiso_price_subscription",
            "agiso_price_subscriptions",
            List.of("user_id", "supplier_account_guid", "product_no")
        );
    }

    @Test
    @DisplayName("orders/users/goods/cards 四表 version 列均为 int unsigned NOT NULL DEFAULT 0")
    void optimisticLockVersionColumnsExist() {
        for (String table : List.of("orders", "users", "goods", "cards")) {
            assertColumn(table, "version", "int unsigned", false);
            Map<String, Object> column = column(table, "version");
            assertThat(String.valueOf(column.get("COLUMN_DEFAULT")))
                .as("%s.version 默认值必须是 0", table)
                .isEqualTo("0");
        }
    }

    @Test
    @DisplayName("重建后库里存在全部核心业务表，且没有残留的迁移存储过程")
    void schemaReplayLeftNoResidue() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'",
            String.class, ItDatabase.DATABASE_NAME);
        assertThat(tables).contains(
            "categories", "user_groups", "users", "goods", "cards", "card_kinds", "orders",
            "payment_records", "payment_callback_logs", "refund_records", "user_balance_transactions",
            "system_settings", "delivery_tasks", "admin_operation_logs", "agiso_price_subscriptions",
            "agiso_rejected_orders");

        List<String> routines = jdbcTemplate.queryForList(
            "SELECT ROUTINE_NAME FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = ?",
            String.class, ItDatabase.DATABASE_NAME);
        assertThat(routines)
            .as("迁移脚本末尾应把 add_column_if_missing 等临时过程删掉")
            .doesNotContain("add_column_if_missing", "add_index_if_missing", "modify_column_if_not_nullable");
    }

    // ------------------------------------------------------------------ 辅助断言

    private void assertTableExists(String table) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
            Integer.class, ItDatabase.DATABASE_NAME, table);
        assertThat(count).as("表 %s 必须存在", table).isEqualTo(1);
    }

    private Map<String, Object> column(String table, String columnName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?
            """, ItDatabase.DATABASE_NAME, table, columnName);
        assertThat(rows).as("列 %s.%s 必须存在", table, columnName).hasSize(1);
        return rows.get(0);
    }

    private void assertColumn(String table, String columnName, String expectedType, boolean nullable) {
        Map<String, Object> row = column(table, columnName);
        assertThat(String.valueOf(row.get("COLUMN_TYPE")).toLowerCase(Locale.ROOT))
            .as("列 %s.%s 类型不符", table, columnName)
            .isEqualTo(expectedType);
        assertThat(String.valueOf(row.get("IS_NULLABLE")))
            .as("列 %s.%s 可空性不符", table, columnName)
            .isEqualTo(nullable ? "YES" : "NO");
    }

    private void assertUniqueIndexColumns(String indexName, String table, List<String> expectedColumns) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT COLUMN_NAME, NON_UNIQUE, SEQ_IN_INDEX
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND INDEX_NAME = ?
            ORDER BY SEQ_IN_INDEX
            """, ItDatabase.DATABASE_NAME, table, indexName);
        assertThat(rows).as("唯一索引 %s.%s 必须存在", table, indexName).hasSize(expectedColumns.size());
        assertThat(rows.stream().map(row -> String.valueOf(row.get("COLUMN_NAME"))).toList())
            .as("唯一索引 %s.%s 列组成不符", table, indexName)
            .containsExactlyElementsOf(expectedColumns);
        assertThat(rows.stream().allMatch(row -> "0".equals(String.valueOf(row.get("NON_UNIQUE")))))
            .as("%s.%s 必须是 UNIQUE 索引", table, indexName)
            .isTrue();
    }

    private String sha256Hex(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
