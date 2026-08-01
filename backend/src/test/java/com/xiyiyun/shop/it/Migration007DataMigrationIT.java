package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.it.support.ItDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 批次7 / 任务A 验收：007 必须把 system_settings 里的 9 类配置真正<b>搬进</b>新表，
 * 而不只是建空表；重复执行必须幂等，且不得覆盖割接后写入的新值。
 *
 * <p>为什么不继承 {@link AbstractIntegrationTest}：那个基类在 {@code @BeforeAll} 里
 * 重建全库（001 + 002..007），本测试需要的恰恰是「007 之前的库」——先把 007 建的结构
 * 拆掉、只留老 KV 行，再把 007 跑一遍看数据有没有过来。所以这里走纯 JDBC，
 * 并在 {@code @AfterAll} 把库结构复原，避免污染后续测试类。
 *
 * <p>安全性：连接由 {@link ItDatabase#openConnection()} 提供，它会校验
 * {@code SELECT DATABASE()} 必须等于 {@code xiyiyun_test}，连错库立即失败。
 * 断言里出现的 bcrypt 串是本测试自造的假值，不是任何真实口令哈希。
 */
class Migration007DataMigrationIT {

    /** 007 新建的 8 张表；模拟老库时要全部拆掉。 */
    private static final List<String> NEW_TABLES = List.of(
        "user_credentials",
        "admin_staff",
        "admin_super_credentials",
        "payment_channels",
        "price_templates",
        "sms_login_settings",
        "captcha_settings",
        "product_monitor_states");

    /** 007 给 member_api_credentials 补的列。 */
    private static final List<String> NEW_MEMBER_COLUMNS = List.of(
        "app_secret_masked",
        "app_secret_ciphertext",
        "app_secret_nonce",
        "app_secret_key_version",
        "app_secret_hash",
        "updated_at");

    private static final String FAKE_MEMBER_HASH =
        "0000000000000000000000000000000000000000000000000000000000000001";

    @BeforeAll
    static void buildFullSchema() {
        // 不依赖测试类执行顺序：先把完整结构摆好，再由 regressToPre007() 退回老形态。
        ItDatabase.rebuildSchema();
    }

    @AfterAll
    static void restoreSchema() {
        // 本测试故意把库退回 007 之前的形态，跑完必须复原，否则后面的测试类会踩到残缺结构。
        ItDatabase.rebuildSchema();
    }

    @Test
    @DisplayName("007 把 9 类老 KV 配置搬进新表，且重复执行幂等、不覆盖割接后的新值")
    void migrationMovesLegacySettingsAndIsIdempotent() throws SQLException {
        try (Connection connection = ItDatabase.openConnection()) {
            connection.setAutoCommit(true);
            regressToPre007(connection);
            seedLegacySettings(connection);

            // ---------- 第一次执行 007 ----------
            ItDatabase.executeScript(connection, ItDatabase.migration007Resource());

            assertUserCredentialsMigrated(connection);
            assertAdminStaffMigrated(connection);
            assertSuperAdminMigrated(connection);
            assertPaymentChannelsMigrated(connection);
            assertPriceTemplatesMigrated(connection);
            assertSmsLoginMigrated(connection);
            assertCaptchaMigrated(connection);
            assertMemberCredentialsMigrated(connection);
            assertMonitorStatesMigrated(connection);

            // 割接标记写上了，且老 KV 行一行都没删（唯一的回滚证据）。
            assertThat(scalar(connection, "SELECT setting_value FROM system_settings WHERE setting_key='config.tables.cutover'"))
                .isEqualTo("007");
            assertThat(count(connection, "SELECT COUNT(*) FROM system_settings WHERE setting_key LIKE 'user.password.%'"))
                .as("007 不得删除 system_settings 老行")
                .isEqualTo(4);
            assertThat(scalar(connection, "SELECT setting_value FROM system_settings WHERE setting_key='admin.staff.accounts'"))
                .isNotBlank();

            // ---------- 模拟割接后应用改了数据 ----------
            exec(connection, "UPDATE user_credentials SET password_hash='$2y$10$POSTCUTOVER_CHANGED_HASH_VALUE_AAAAAAAAAAAAAAAAAAAAAA' WHERE user_id=101");
            exec(connection, "DELETE FROM admin_staff WHERE id=9002");
            exec(connection, "UPDATE payment_channels SET status='DISABLED' WHERE id=201");
            exec(connection, "UPDATE price_templates SET name='割接后改名' WHERE template_id='tpl-a'");
            exec(connection, "UPDATE sms_login_settings SET provider='ALIYUN' WHERE id=1");
            exec(connection, "UPDATE captcha_settings SET enabled=0 WHERE id=1");
            exec(connection, "UPDATE admin_super_credentials SET password_hash='$2y$10$POSTCUTOVER_SUPER_HASH_BBBBBBBBBBBBBBBBBBBBBBBBBBBB' WHERE id=1");
            exec(connection, "UPDATE member_api_credentials SET status='DISABLED' WHERE user_id=101");

            Map<String, Long> before = tableCounts(connection);

            // ---------- 第二次执行 007（幂等） ----------
            ItDatabase.executeScript(connection, ItDatabase.migration007Resource());

            assertThat(tableCounts(connection))
                .as("重复执行 007 不得改变行数（admin_staff 被删掉的那行不得复活）")
                .isEqualTo(before);
            assertThat(scalar(connection, "SELECT password_hash FROM user_credentials WHERE user_id=101"))
                .as("重复执行不得把割接后改过的口令哈希覆盖回老值")
                .isEqualTo("$2y$10$POSTCUTOVER_CHANGED_HASH_VALUE_AAAAAAAAAAAAAAAAAAAAAA");
            assertThat(count(connection, "SELECT COUNT(*) FROM admin_staff WHERE id=9002"))
                .as("已删除的后台员工不得被第二次迁移复活成可登录的僵尸账号")
                .isZero();
            assertThat(scalar(connection, "SELECT status FROM payment_channels WHERE id=201")).isEqualTo("DISABLED");
            assertThat(scalar(connection, "SELECT name FROM price_templates WHERE template_id='tpl-a'")).isEqualTo("割接后改名");
            assertThat(scalar(connection, "SELECT provider FROM sms_login_settings WHERE id=1")).isEqualTo("ALIYUN");
            assertThat(scalar(connection, "SELECT enabled FROM captcha_settings WHERE id=1")).isEqualTo("0");
            assertThat(scalar(connection, "SELECT password_hash FROM admin_super_credentials WHERE id=1"))
                .isEqualTo("$2y$10$POSTCUTOVER_SUPER_HASH_BBBBBBBBBBBBBBBBBBBBBBBBBBBB");
            assertThat(scalar(connection, "SELECT status FROM member_api_credentials WHERE user_id=101")).isEqualTo("DISABLED");
        }
    }

    // ------------------------------------------------------------------ 断言

    private void assertUserCredentialsMigrated(Connection connection) throws SQLException {
        // 4 个老 key 里只有 2 个合法：101/102。noise（非数字后缀）与空值行必须被跳过。
        assertThat(count(connection, "SELECT COUNT(*) FROM user_credentials")).isEqualTo(2);
        assertThat(scalar(connection, "SELECT password_hash FROM user_credentials WHERE user_id=101"))
            .isEqualTo("$2y$10$LEGACYUSERONEHASHAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertThat(scalar(connection, "SELECT password_hash FROM user_credentials WHERE user_id=102"))
            .isEqualTo("$2y$10$LEGACYUSERTWOHASHBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
    }

    private void assertAdminStaffMigrated(Connection connection) throws SQLException {
        // 3 个条目：9001 正常、9002 正常、第三个 passwordHash 为空必须跳过。
        assertThat(count(connection, "SELECT COUNT(*) FROM admin_staff")).isEqualTo(2);
        assertThat(scalar(connection, "SELECT account FROM admin_staff WHERE id=9001")).isEqualTo("ops-alice");
        assertThat(scalar(connection, "SELECT nickname FROM admin_staff WHERE id=9001")).isEqualTo("运营A");
        assertThat(scalar(connection, "SELECT status FROM admin_staff WHERE id=9001")).isEqualTo("ENABLED");
        assertThat(scalar(connection, "SELECT password_hash FROM admin_staff WHERE id=9001"))
            .isEqualTo("$2y$10$LEGACYSTAFFONEHASHCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC");
        assertThat(scalar(connection, "SELECT JSON_EXTRACT(permissions, '$[0]') FROM admin_staff WHERE id=9001"))
            .isEqualTo("\"order.view\"");
        // 空 status 落默认值 ENABLED；createdAt 的 +08:00 偏移要被正确换算。
        assertThat(scalar(connection, "SELECT status FROM admin_staff WHERE id=9002")).isEqualTo("ENABLED");
        assertThat(scalar(connection, "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') FROM admin_staff WHERE id=9002"))
            .isEqualTo("2026-03-01 10:20:30");
    }

    private void assertSuperAdminMigrated(Connection connection) throws SQLException {
        assertThat(count(connection, "SELECT COUNT(*) FROM admin_super_credentials")).isEqualTo(1);
        assertThat(scalar(connection, "SELECT username FROM admin_super_credentials WHERE id=1")).isEqualTo("root-admin");
        assertThat(scalar(connection, "SELECT nickname FROM admin_super_credentials WHERE id=1")).isEqualTo("超级管理员");
        assertThat(scalar(connection, "SELECT password_hash FROM admin_super_credentials WHERE id=1"))
            .isEqualTo("$2y$10$LEGACYSUPERHASHDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD");
    }

    private void assertPaymentChannelsMigrated(Connection connection) throws SQLException {
        // 2 个条目：201 正常、第二个 code 为空必须跳过。
        assertThat(count(connection, "SELECT COUNT(*) FROM payment_channels")).isEqualTo(1);
        assertThat(scalar(connection, "SELECT code FROM payment_channels WHERE id=201")).isEqualTo("wechat");
        assertThat(scalar(connection, "SELECT channel_type FROM payment_channels WHERE id=201")).isEqualTo("WECHAT");
        assertThat(scalar(connection, "SELECT sort_no FROM payment_channels WHERE id=201")).isEqualTo("20");
        // 公开配置与密文信封分列，且密文列里不含明文。
        assertThat(scalar(connection, "SELECT JSON_UNQUOTE(JSON_EXTRACT(config_public, '$.app_id')) FROM payment_channels WHERE id=201"))
            .isEqualTo("wx-public-app");
        assertThat(scalar(connection, "SELECT JSON_UNQUOTE(JSON_EXTRACT(config_secrets, '$.private_key.ciphertext')) FROM payment_channels WHERE id=201"))
            .isEqualTo("cipher-blob-1");
        assertThat(scalar(connection, "SELECT CONCAT(config_public, '|', config_secrets) FROM payment_channels WHERE id=201"))
            .doesNotContain("PLAINTEXT-SHOULD-NEVER-APPEAR");
        assertThat(scalar(connection, "SELECT JSON_EXTRACT(terminals, '$[0]') FROM payment_channels WHERE id=201"))
            .isEqualTo("\"h5\"");
    }

    private void assertPriceTemplatesMigrated(Connection connection) throws SQLException {
        assertThat(count(connection, "SELECT COUNT(*) FROM price_templates")).isEqualTo(2);
        // 数组顺序靠 sort_no 保留：第 1 个 -> 10，第 2 个 -> 20。
        assertThat(scalar(connection, "SELECT sort_no FROM price_templates WHERE template_id='tpl-a'")).isEqualTo("10");
        assertThat(scalar(connection, "SELECT sort_no FROM price_templates WHERE template_id='tpl-b'")).isEqualTo("20");
        assertThat(scalar(connection, "SELECT adjust_mode FROM price_templates WHERE template_id='tpl-a'")).isEqualTo("PERCENT");
        assertThat(scalar(connection, "SELECT reference_price FROM price_templates WHERE template_id='tpl-a'")).isEqualTo("12.5000");
        assertThat(scalar(connection, "SELECT enabled FROM price_templates WHERE template_id='tpl-b'"))
            .as("enabled=false 必须落成 0，不能被 COALESCE 吃成默认 1")
            .isEqualTo("0");
        // adjustMode 缺省时落默认 PERCENT
        assertThat(scalar(connection, "SELECT adjust_mode FROM price_templates WHERE template_id='tpl-b'")).isEqualTo("PERCENT");
    }

    private void assertSmsLoginMigrated(Connection connection) throws SQLException {
        assertThat(count(connection, "SELECT COUNT(*) FROM sms_login_settings")).isEqualTo(1);
        assertThat(scalar(connection, "SELECT enabled FROM sms_login_settings WHERE id=1")).isEqualTo("1");
        assertThat(scalar(connection, "SELECT admin_login_enabled FROM sms_login_settings WHERE id=1")).isEqualTo("0");
        assertThat(scalar(connection, "SELECT provider FROM sms_login_settings WHERE id=1")).isEqualTo("TENCENT");
        assertThat(scalar(connection, "SELECT admin_mobile FROM sms_login_settings WHERE id=1")).isEqualTo("13800000000");
        assertThat(scalar(connection, "SELECT code_length FROM sms_login_settings WHERE id=1")).isEqualTo("6");
        assertThat(scalar(connection, "SELECT ttl_seconds FROM sms_login_settings WHERE id=1")).isEqualTo("300");
        assertThat(scalar(connection, "SELECT JSON_UNQUOTE(JSON_EXTRACT(tencent_config_public, '$.sdkAppId')) FROM sms_login_settings WHERE id=1"))
            .isEqualTo("sms-public-1");
        assertThat(scalar(connection, "SELECT JSON_UNQUOTE(JSON_EXTRACT(tencent_config_secrets, '$.secretKey.ciphertext')) FROM sms_login_settings WHERE id=1"))
            .isEqualTo("sms-cipher-1");
    }

    private void assertCaptchaMigrated(Connection connection) throws SQLException {
        assertThat(count(connection, "SELECT COUNT(*) FROM captcha_settings")).isEqualTo(1);
        assertThat(scalar(connection, "SELECT enabled FROM captcha_settings WHERE id=1")).isEqualTo("1");
        assertThat(scalar(connection, "SELECT provider FROM captcha_settings WHERE id=1")).isEqualTo("TURNSTILE");
        assertThat(scalar(connection, "SELECT JSON_UNQUOTE(JSON_EXTRACT(turnstile_config_public, '$.siteKey')) FROM captcha_settings WHERE id=1"))
            .isEqualTo("cap-public-1");
        assertThat(scalar(connection, "SELECT JSON_UNQUOTE(JSON_EXTRACT(turnstile_config_secrets, '$.secretKey.ciphertext')) FROM captcha_settings WHERE id=1"))
            .isEqualTo("cap-cipher-1");
    }

    private void assertMemberCredentialsMigrated(Connection connection) throws SQLException {
        // 3 个老 key：101/102 合法，第三个 appKey 为空必须跳过。
        assertThat(count(connection, "SELECT COUNT(*) FROM member_api_credentials")).isEqualTo(2);
        assertThat(scalar(connection, "SELECT app_key FROM member_api_credentials WHERE user_id=101")).isEqualTo("ak-101");
        assertThat(scalar(connection, "SELECT app_secret_masked FROM member_api_credentials WHERE user_id=101")).isEqualTo("ak-1****-101");
        assertThat(scalar(connection, "SELECT app_secret_ciphertext FROM member_api_credentials WHERE user_id=101")).isEqualTo("mac-cipher-101");
        assertThat(scalar(connection, "SELECT app_secret_nonce FROM member_api_credentials WHERE user_id=101")).isEqualTo("mac-nonce-101");
        assertThat(scalar(connection, "SELECT app_secret_key_version FROM member_api_credentials WHERE user_id=101")).isEqualTo("v1");
        assertThat(scalar(connection, "SELECT app_secret_hash FROM member_api_credentials WHERE user_id=101")).isEqualTo(FAKE_MEMBER_HASH);
        assertThat(scalar(connection, "SELECT daily_limit FROM member_api_credentials WHERE user_id=101")).isEqualTo("2000");
        assertThat(scalar(connection, "SELECT app_secret FROM member_api_credentials WHERE user_id=101"))
            .as("明文列必须为 NULL：KV 里从来只有掩码 + 密文信封")
            .isNull();
        // 老 KV 里的 id 字段（内存计数器分配，用户间可能重复）不得当主键用，
        // 否则第二个用户会被静默丢弃。
        assertThat(count(connection, "SELECT COUNT(*) FROM member_api_credentials WHERE user_id=102")).isEqualTo(1);
        assertThat(scalar(connection, "SELECT status FROM member_api_credentials WHERE user_id=102")).isEqualTo("ENABLED");
    }

    private void assertMonitorStatesMigrated(Connection connection) throws SQLException {
        // 3 个老 key：30001 正常、30002 是批次6 的空串墓碑（必须跳过）、noise 非数字后缀。
        assertThat(count(connection, "SELECT COUNT(*) FROM product_monitor_states")).isEqualTo(1);
        assertThat(count(connection, "SELECT COUNT(*) FROM product_monitor_states WHERE channel_id=30002"))
            .as("批次6 的空串墓碑语义是「不存在」，搬迁后也必须不存在")
            .isZero();
        assertThat(scalar(connection, "SELECT last_result FROM product_monitor_states WHERE channel_id=30001")).isEqualTo("NO_CHANGE");
        assertThat(scalar(connection, "SELECT scan_count FROM product_monitor_states WHERE channel_id=30001")).isEqualTo("7");
        assertThat(scalar(connection, "SELECT change_count FROM product_monitor_states WHERE channel_id=30001")).isEqualTo("2");
        // Z 结尾的 UTC 时间要被正确换算到 +08:00 的会话时区
        assertThat(scalar(connection, "SELECT DATE_FORMAT(next_scan_at, '%Y-%m-%d %H:%i:%s') FROM product_monitor_states WHERE channel_id=30001"))
            .isEqualTo("2026-04-01 12:03:00");
    }

    // ------------------------------------------------------- 造「007 之前的库」

    /** 把 007 的结构性改动全部拆掉，让库回到只有 001..006 的形态。 */
    private void regressToPre007(Connection connection) throws SQLException {
        for (String table : NEW_TABLES) {
            exec(connection, "DROP TABLE IF EXISTS `" + table + "`");
        }
        exec(connection, "DELETE FROM member_api_credentials");
        for (String column : NEW_MEMBER_COLUMNS) {
            if (columnExists(connection, "member_api_credentials", column)) {
                exec(connection, "ALTER TABLE member_api_credentials DROP COLUMN `" + column + "`");
            }
        }
        if (indexExists(connection, "member_api_credentials", "uk_member_api_user")) {
            exec(connection, "ALTER TABLE member_api_credentials DROP INDEX uk_member_api_user");
        }
        // 老结构里 app_secret 是 NOT NULL，007 才放宽为 NULL；这里也退回去，
        // 好验证 modify_column_if_not_nullable 真的生效（否则 9.8 写 NULL 会失败）。
        exec(connection, "ALTER TABLE member_api_credentials MODIFY COLUMN app_secret VARCHAR(255) NOT NULL");
        exec(connection, "DELETE FROM system_settings WHERE setting_key='config.tables.cutover'");

        assertThat(columnExists(connection, "member_api_credentials", "app_secret_ciphertext")).isFalse();
        for (String table : NEW_TABLES) {
            assertThat(tableExists(connection, table)).as("%s 应已拆掉，模拟 007 之前的库", table).isFalse();
        }
    }

    /** 灌入老格式 KV 行，含必须被过滤掉的噪声行。 */
    private void seedLegacySettings(Connection connection) throws SQLException {
        exec(connection, "DELETE FROM system_settings WHERE setting_key LIKE 'user.password.%'"
            + " OR setting_key LIKE 'member.credential.%' OR setting_key LIKE 'product.monitor.state.%'"
            + " OR setting_key IN ('admin.staff.accounts','payment.channels','price.templates',"
            + "'sms.login.setting','captcha.setting','admin.super.username','admin.super.passwordHash','admin.super.nickname')");

        // 9.1 会员口令：2 条合法 + 1 条非数字后缀 + 1 条空值
        put(connection, "user.password.101", "$2y$10$LEGACYUSERONEHASHAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        put(connection, "user.password.102", "$2y$10$LEGACYUSERTWOHASHBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        put(connection, "user.password.abc", "$2y$10$SHOULD_BE_SKIPPED_NON_NUMERIC_SUFFIX_XXXXXXXXXXXXXXXX");
        put(connection, "user.password.103", "");

        // 9.2 后台员工：2 条合法 + 1 条缺 passwordHash
        put(connection, "admin.staff.accounts", """
            [
              {"id":9001,"account":"ops-alice","nickname":"运营A","status":"ENABLED",
               "permissions":["order.view","goods.edit"],
               "passwordHash":"$2y$10$LEGACYSTAFFONEHASHCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC",
               "createdAt":"2026-02-01T09:00:00+08:00","updatedAt":"2026-02-02T09:00:00+08:00"},
              {"id":9002,"account":"ops-bob","nickname":"","status":"",
               "permissions":[],
               "passwordHash":"$2y$10$LEGACYSTAFFTWOHASHEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE",
               "createdAt":"2026-03-01T10:20:30+08:00","updatedAt":""},
              {"id":9003,"account":"ops-nohash","nickname":"无口令","status":"ENABLED",
               "permissions":[],"passwordHash":"","createdAt":"","updatedAt":""}
            ]
            """);

        // 9.3 超管
        put(connection, "admin.super.username", "root-admin");
        put(connection, "admin.super.passwordHash", "$2y$10$LEGACYSUPERHASHDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD");
        put(connection, "admin.super.nickname", "超级管理员");

        // 9.4 支付通道：1 条合法 + 1 条 code 为空
        put(connection, "payment.channels", """
            [
              {"id":201,"code":"wechat","name":"微信支付","type":"WECHAT","terminals":["h5","web"],
               "status":"ENABLED","sort":20,
               "config":{"app_id":"wx-public-app","mch_id":"mch-1"},
               "configSecrets":{"private_key":{"ciphertext":"cipher-blob-1","nonce":"nonce-1","keyVersion":"v1","hash":"hash-1"}},
               "remark":"线上主通道",
               "createdAt":"2026-01-05T08:00:00+08:00","updatedAt":"2026-01-06T08:00:00+08:00"},
              {"id":202,"code":"","name":"缺 code 应跳过","type":"ALIPAY","terminals":[],
               "status":"ENABLED","sort":30,"config":{},"configSecrets":{},"remark":"",
               "createdAt":"","updatedAt":""}
            ]
            """);

        // 9.5 价格模板：顺序敏感
        put(connection, "price.templates", """
            [
              {"id":"tpl-a","name":"模板A","adjustMode":"PERCENT","referencePrice":12.5,
               "groupRates":[{"groupId":1,"rate":0.95}],"enabled":true},
              {"id":"tpl-b","name":"模板B","referencePrice":0,"groupRates":[],"enabled":false}
            ]
            """);

        // 9.6 短信登录
        put(connection, "sms.login.setting", """
            {"enabled":true,"adminLoginEnabled":false,"h5LoginEnabled":true,"webLoginEnabled":true,
             "provider":"TENCENT","adminMobile":"13800000000","codeLength":6,"ttlSeconds":300,
             "cooldownSeconds":60,"maxAttempts":5,
             "tencentConfig":{"sdkAppId":"sms-public-1","templateId":"tpl-1"},
             "tencentConfigSecrets":{"secretKey":{"ciphertext":"sms-cipher-1","nonce":"sms-nonce-1","keyVersion":"v1","hash":"sms-hash-1"}}}
            """);

        // 9.7 图形验证
        put(connection, "captcha.setting", """
            {"enabled":true,"adminLoginEnabled":true,"h5LoginEnabled":false,"webLoginEnabled":true,
             "provider":"TURNSTILE",
             "turnstileConfig":{"siteKey":"cap-public-1"},
             "turnstileConfigSecrets":{"secretKey":{"ciphertext":"cap-cipher-1","nonce":"cap-nonce-1","keyVersion":"v1","hash":"cap-hash-1"}}}
            """);

        // 9.8 会员 API 凭据：注意两条的 id 都是 1（老内存计数器的重复 id），
        //     必须按 user_id 去重而不是按 id，否则第二条会被丢掉。
        put(connection, "member.credential.101", """
            {"id":1,"userId":101,"appKey":"ak-101","appSecretMasked":"ak-1****-101",
             "appSecretCiphertext":"mac-cipher-101","appSecretNonce":"mac-nonce-101",
             "appSecretKeyVersion":"v1",
             "appSecretHash":"0000000000000000000000000000000000000000000000000000000000000001",
             "status":"ENABLED","ipWhitelist":["10.0.0.1"],"dailyLimit":2000,
             "createdAt":"2026-01-02T03:04:05+08:00","lastUsedAt":""}
            """);
        put(connection, "member.credential.102", """
            {"id":1,"userId":102,"appKey":"ak-102","appSecretMasked":"ak-1****-102",
             "appSecretCiphertext":"mac-cipher-102","appSecretNonce":"mac-nonce-102",
             "appSecretKeyVersion":"v1",
             "appSecretHash":"0000000000000000000000000000000000000000000000000000000000000002",
             "status":"ENABLED","ipWhitelist":[],"dailyLimit":1000,
             "createdAt":"2026-01-03T03:04:05+08:00","lastUsedAt":"2026-01-04T05:06:07+08:00"}
            """);
        put(connection, "member.credential.103", "{\"id\":3,\"userId\":103,\"appKey\":\"\",\"status\":\"ENABLED\"}");

        // 9.9 监控状态：1 条正常 + 1 条批次6 空串墓碑 + 1 条非数字后缀
        put(connection, "product.monitor.state.30001", """
            {"channelId":30001,"lastScanAt":"2026-04-01T04:00:00Z","nextScanAt":"2026-04-01T04:03:00Z",
             "lastResult":"NO_CHANGE","lastMessage":"未发现变动","scanCount":7,"changeCount":2,"scanning":true}
            """);
        put(connection, "product.monitor.state.30002", "");
        put(connection, "product.monitor.state.bad", "{\"lastResult\":\"NO_CHANGE\"}");
    }

    // ------------------------------------------------------------------ JDBC

    private Map<String, Long> tableCounts(Connection connection) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>(NEW_TABLES);
        tables.add("member_api_credentials");
        for (String table : tables) {
            counts.put(table, count(connection, "SELECT COUNT(*) FROM `" + table + "`"));
        }
        return counts;
    }

    private void put(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO system_settings (setting_key, setting_value) VALUES (?, ?)"
                + " ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private void exec(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        return count(connection, "SELECT COUNT(*) FROM information_schema.TABLES"
            + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "'") > 0;
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        return count(connection, "SELECT COUNT(*) FROM information_schema.COLUMNS"
            + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "'"
            + " AND COLUMN_NAME = '" + column + "'") > 0;
    }

    private boolean indexExists(Connection connection, String table, String index) throws SQLException {
        return count(connection, "SELECT COUNT(*) FROM information_schema.STATISTICS"
            + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "'"
            + " AND INDEX_NAME = '" + index + "'") > 0;
    }
}
