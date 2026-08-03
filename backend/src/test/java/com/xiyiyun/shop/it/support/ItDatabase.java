package com.xiyiyun.shop.it.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 集成测试库（xiyiyun_test）的连接信息与结构重建。
 *
 * <p>三条硬约束在这里落地：
 * <ol>
 *   <li><b>只能连 xiyiyun_test。</b>URL 里的库名与实际 {@code DATABASE()} 都会被校验，
 *       连到 xiyiyun（含用户手工录入数据）直接抛异常终止。</li>
 *   <li><b>库连不上必须响亮失败。</b>不做 {@code Assumptions.assumeTrue} 之类的静默跳过，
 *       连接失败会抛出带完整诊断信息的异常，测试计为 error。</li>
 *   <li><b>结构来自真实脚本。</b>按 001 → 002 → ... → 006 顺序重放，不手写建表语句。</li>
 * </ol>
 */
public final class ItDatabase {

    public static final String DATABASE_NAME = "xiyiyun_test";

    /** 容器内经 compose 网络访问 MySQL 用主机名 mysql:3306。 */
    public static final String URL = System.getenv().getOrDefault(
        "IT_DATASOURCE_URL",
        "jdbc:mysql://mysql:3306/" + DATABASE_NAME
            + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"
            + "&allowPublicKeyRetrieval=true&useSSL=false");
    public static final String USERNAME = System.getenv().getOrDefault("IT_DATASOURCE_USERNAME", "xiyiyun");
    public static final String PASSWORD = System.getenv().getOrDefault("IT_DATASOURCE_PASSWORD", "xiyiyun_pwd");
    public static final String REDIS_HOST = System.getenv().getOrDefault("IT_REDIS_HOST", "redis");
    public static final String CARD_ENCRYPTION_SECRET = "xiyiyun_it_card_secret";
    public static final String PAYMENT_CALLBACK_SECRET = "xiyiyun_it_payment_secret";

    private static final String SCHEMA_RESOURCE = "db/init/001_schema.sql";
    private static final List<String> MIGRATION_RESOURCES = List.of(
        "db/migrations/002_config_persistence.sql",
        "db/migrations/003_category_icons.sql",
        "db/migrations/004_security_and_order_consistency.sql",
        "db/migrations/005_cards_kind_and_callback_logs.sql",
        "db/migrations/006_money_integrity.sql",
        "db/migrations/007_config_tables_extraction.sql",
        "db/migrations/008_user_username.sql",
        "db/migrations/009_agiso_callback_tasks.sql",
        "db/migrations/010_agiso_price_subscriptions.sql",
        "db/migrations/011_member_api_callback_url.sql",
        "db/migrations/012_member_order_callback_tasks.sql",
        "db/migrations/013_order_buyer_account.sql",
        "db/migrations/014_order_external_max_amount.sql",
        "db/migrations/015_wecom_robot_delivery_tasks.sql",
        "db/migrations/016_supplier_price_history.sql");

    private ItDatabase() {
    }

    public static List<String> schemaScriptResources() {
        List<String> all = new ArrayList<>();
        all.add(SCHEMA_RESOURCE);
        all.addAll(MIGRATION_RESOURCES);
        return List.copyOf(all);
    }

    static {
        // 防呆：URL 必须落在 xiyiyun_test 上
        if (!URL.contains("/" + DATABASE_NAME + "?") && !URL.endsWith("/" + DATABASE_NAME)) {
            throw new IllegalStateException(
                "集成测试数据源必须指向 " + DATABASE_NAME + " 库，实际 URL=" + URL);
        }
    }

    /**
     * 打开一个测试库连接。连不上时抛出带诊断信息的异常，绝不静默跳过。
     */
    public static Connection openConnection() {
        try {
            Connection connection = DriverManager.getConnection(URL, USERNAME, PASSWORD);
            assertConnectedToTestDatabase(connection);
            return connection;
        } catch (SQLException ex) {
            throw new IllegalStateException(
                "无法连接集成测试数据库，集成测试无法运行（这是失败，不是跳过）。\n"
                    + "  url=" + URL + "\n"
                    + "  user=" + USERNAME + "\n"
                    + "  原因=" + ex.getMessage() + "\n"
                    + "排查：MySQL 容器 xiyiyun-mysql 是否在运行；库 " + DATABASE_NAME + " 是否存在；"
                    + "用户 " + USERNAME + " 是否已授权；测试是否在 compose 网络内（容器里用主机名 mysql）。",
                ex);
        }
    }

    /** 校验实际连上的库就是 xiyiyun_test；否则立刻失败，避免误写生产库。 */
    public static void assertConnectedToTestDatabase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT DATABASE()")) {
            String actual = rs.next() ? rs.getString(1) : null;
            if (!DATABASE_NAME.equals(actual)) {
                throw new IllegalStateException(
                    "集成测试连到了错误的数据库: " + actual + "，只允许 " + DATABASE_NAME
                        + "。为保护 xiyiyun 生产库中的手工数据，测试立即终止。");
            }
        }
    }

    /**
     * 重建整套结构：先删掉库里所有表与遗留存储过程，再按 001 → 002..006 顺序重放真实脚本。
     *
     * <p>必须先 DROP：脚本里全是 {@code CREATE TABLE IF NOT EXISTS}，若不清空，
     * 上一个测试类留下的旧结构会被直接沿用，"按真实结构建库"就失去意义。
     */
    public static void rebuildSchema() {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(true);
            dropEverything(connection);
            for (String resource : schemaScriptResources()) {
                SqlScriptRunner.execute(connection, resource, readResource(resource));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("重建 " + DATABASE_NAME + " 结构失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 在已连上的测试库上重放单个脚本资源。
     *
     * <p>给「数据搬迁 / 幂等」类测试用：它们需要在同一个库上把某个迁移跑第二遍，
     * 而 {@link #rebuildSchema()} 会先清空全库，不能表达这种场景。
     */
    public static void executeScript(Connection connection, String resource) throws SQLException {
        assertConnectedToTestDatabase(connection);
        SqlScriptRunner.execute(connection, resource, readResource(resource));
    }

    /** 007 的资源路径，供搬迁测试重放。 */
    public static String migration007Resource() {
        return "db/migrations/007_config_tables_extraction.sql";
    }

    private static void dropEverything(Connection connection) throws SQLException {
        assertConnectedToTestDatabase(connection);
        List<String> tables = queryStrings(connection,
            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = '" + DATABASE_NAME + "'"
                + " AND TABLE_TYPE = 'BASE TABLE'");
        List<String> views = queryStrings(connection,
            "SELECT TABLE_NAME FROM information_schema.VIEWS WHERE TABLE_SCHEMA = '" + DATABASE_NAME + "'");
        List<String> routines = queryStrings(connection,
            "SELECT ROUTINE_NAME FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = '" + DATABASE_NAME + "'"
                + " AND ROUTINE_TYPE = 'PROCEDURE'");
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String view : views) {
                statement.execute("DROP VIEW IF EXISTS `" + view + "`");
            }
            for (String table : tables) {
                statement.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
            for (String routine : routines) {
                statement.execute("DROP PROCEDURE IF EXISTS `" + routine + "`");
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    private static List<String> queryStrings(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    public static String readResource(String resource) {
        ClassLoader loader = ItDatabase.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                    "classpath 上找不到 " + resource + "。集成测试依赖 backend/src/test/resources/db/ 下的脚本副本，"
                        + "因为 backend 容器只挂载了 ./backend，仓库根目录的 db/ 在容器内不可见。");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("读取 " + resource + " 失败", ex);
        }
    }
}
