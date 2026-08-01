package com.xiyiyun.shop.it.support;

import com.xiyiyun.shop.persistence.CardCipherService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试数据准备。
 *
 * <p>为什么必须直接往 MySQL 插数据、而不是调 repository 的种子方法：
 * {@code InMemoryShopRepository} 构造函数里 {@code if (persistenceEnabled()) return;}
 * 会在存在任何持久化 Bean 时**跳过全部内存种子数据**。集成测试 profile 下持久化 Bean 都在，
 * 因此内存 Map 起始为空，数据唯一来源就是数据库，repository 通过 findXxxSnapshot 读穿到库里。
 *
 * <p>既有的 {@code persistence/fixture/TestCardFixtureGenerator} 是给「批量导入 TSV 文件」用的
 * 离线生成器，不是数据库 fixture，无法直接复用；可复用的只有它对 {@link CardCipherService}
 * 的用法（密文 / nonce / key_version / sha256 hash 四件套），本类沿用同一套算法，
 * 保证 fixture 卡密能被生产代码解密、且 card_hash 与唯一索引 uk_cards_kind_hash 语义一致。
 */
public class ItFixtures {

    /** 业务表清单：每个测试方法前清空，避免互相污染。 */
    private static final List<String> BUSINESS_TABLES = List.of(
        "member_order_callback_tasks",
        "order_status_logs",
        "payment_callback_logs",
        "payment_records",
        "refund_records",
        "delivery_tasks",
        "orders",
        "user_balance_transactions",
        "cards",
        "goods_available_platform",
        "goods_forbidden_platform",
        "goods_channels",
        "goods",
        "card_kinds",
        "group_goods_rules",
        "member_api_credentials",
        "open_api_logs",
        "sms_logs",
        "admin_operation_logs",
        "users",
        "user_groups",
        "categories",
        "suppliers");

    public static final long GROUP_ID = 1L;
    public static final long CATEGORY_ID = 900L;
    public static final long USER_ID = 9001L;
    public static final long GOODS_ID = 8001L;
    public static final long CARD_KIND_ID = 7001L;

    private final JdbcTemplate jdbcTemplate;
    private final CardCipherService cardCipherService;

    public ItFixtures(JdbcTemplate jdbcTemplate, CardCipherService cardCipherService) {
        this.jdbcTemplate = jdbcTemplate;
        this.cardCipherService = cardCipherService;
    }

    /** 清空业务表。system_settings / sales_platforms 保留：它们在上下文启动时已被读入内存。 */
    public void truncateBusinessTables() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : BUSINESS_TABLES) {
                jdbcTemplate.execute("TRUNCATE TABLE `" + table + "`");
            }
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    // ---------------------------------------------------------------- 基础主数据

    public void insertUserGroup() {
        jdbcTemplate.update("""
            INSERT INTO user_groups (id, name, description, is_default, status, order_enabled,
                real_name_required_for_order, price_limit_enabled)
            VALUES (?, ?, ?, 1, 'ENABLED', 1, 0, 1)
            """, GROUP_ID, "IT默认会员", "集成测试用户组");
    }

    public void insertCategory() {
        jdbcTemplate.update("""
            INSERT INTO categories (id, parent_id, name, sort_no, status)
            VALUES (?, NULL, ?, 1, 'ON_SALE')
            """, CATEGORY_ID, "IT测试分类");
    }

    /** 建用户；balance 是后续资金断言的起点。 */
    public long insertUser(BigDecimal balance) {
        return insertUser(USER_ID, "13900000001", balance);
    }

    public long insertUser(long userId, String mobile, BigDecimal balance) {
        jdbcTemplate.update("""
            INSERT INTO users (id, mobile, email, nickname, group_id, balance, deposit,
                real_name_type, verification_status, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, 0, 'NONE', 'UNVERIFIED', 'NORMAL', ?)
            """, userId, mobile, "it" + userId + "@example.com", "IT用户" + userId, GROUP_ID,
            balance, OffsetDateTime.now());
        return userId;
    }

    public void insertCardKind() {
        jdbcTemplate.update("""
            INSERT INTO card_kinds (id, name, type, cost) VALUES (?, ?, 'TEXT', 1.0000)
            """, CARD_KIND_ID, "IT卡种");
    }

    /**
     * 建商品。{@code delivery_template} 里放 CatalogPersistenceMapper 会读的扩展字段
     * （cardKindId 决定卡密从哪个卡种取，availablePlatforms 放开全部端）。
     */
    public long insertGoods(String goodsType, int stockCount, BigDecimal salePrice, Long cardKindId) {
        return insertGoods(GOODS_ID, "IT测试商品", goodsType, stockCount, salePrice, cardKindId);
    }

    public long insertGoods(long goodsId, String name, String goodsType, int stockCount,
                            BigDecimal salePrice, Long cardKindId) {
        String template = cardKindId == null
            ? "{\"availablePlatforms\":[\"all\"],\"forbiddenPlatforms\":[]}"
            : "{\"availablePlatforms\":[\"all\"],\"forbiddenPlatforms\":[],\"cardKindId\":" + cardKindId + "}";
        jdbcTemplate.update("""
            INSERT INTO goods (id, category_id, name, goods_type, status, face_value, sale_price,
                cost_price, stock_mode, stock_count, min_qty, max_qty, delivery_template, sort_no,
                description, created_at)
            VALUES (?, ?, ?, ?, 'ON_SALE', ?, ?, 0, 'LOCAL', ?, 1, NULL, CAST(? AS JSON), 0, ?, ?)
            """, goodsId, CATEGORY_ID, name, goodsType, salePrice, salePrice, stockCount,
            template, "集成测试商品", OffsetDateTime.now());
        return goodsId;
    }

    /** 一次建齐「用户组 + 分类 + 用户 + 卡种 + 卡密商品」。 */
    public void seedCardGoodsScenario(BigDecimal userBalance, int stockCount, BigDecimal salePrice) {
        insertUserGroup();
        insertCategory();
        insertUser(userBalance);
        insertCardKind();
        insertGoods("CARD", stockCount, salePrice, CARD_KIND_ID);
    }

    // ---------------------------------------------------------------- 卡密

    /**
     * 插入一张 UNSOLD 卡密，密文用生产同款 {@link CardCipherService} 生成，
     * 因此 {@code PersistentOrderStore.deliverCardsForOrder} 能正常解密。
     */
    public long insertUnsoldCard(String content) {
        return insertUnsoldCard(CARD_KIND_ID, GOODS_ID, content);
    }

    public long insertUnsoldCard(Long cardKindId, Long goodsId, String content) {
        CardCipherService.EncryptedCard encrypted = cardCipherService.encrypt(content);
        jdbcTemplate.update("""
            INSERT INTO cards (goods_id, card_kind_id, batch_no, card_ciphertext, card_nonce,
                card_key_version, card_hash, card_preview, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'UNSOLD', ?)
            """, goodsId, cardKindId, "IT-BATCH", encrypted.ciphertext(), encrypted.nonce(),
            encrypted.keyVersion(), encrypted.hash(), preview(content), OffsetDateTime.now());
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String preview(String content) {
        return content.length() <= 4 ? content : content.substring(0, 4) + "****";
    }

    // ---------------------------------------------------------------- 订单 / 支付

    /** 直接写一条订单快照（绕过业务流程），用于回调重放等需要预置状态的用例。 */
    public long insertOrder(String orderNo, long userId, long goodsId, String goodsType, String status,
                            int quantity, BigDecimal unitPrice) {
        jdbcTemplate.update("""
            INSERT INTO orders (order_no, user_id, source_platform_code, goods_id, goods_name, goods_type,
                quantity, unit_price, total_amount, pay_amount, status, delivery_status, created_at)
            VALUES (?, ?, 'h5', ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
            """, orderNo, userId, goodsId, "IT测试商品", goodsType, quantity, unitPrice,
            unitPrice.multiply(BigDecimal.valueOf(quantity)), unitPrice.multiply(BigDecimal.valueOf(quantity)),
            status, OffsetDateTime.now());
        return jdbcTemplate.queryForObject(
            "SELECT id FROM orders WHERE order_no = ?", Long.class, orderNo);
    }

    /** 写一条 PENDING 的余额支付记录，供支付回调用例驱动。 */
    public void insertPendingPayment(String paymentNo, String orderNo, long orderId, long userId,
                                     BigDecimal amount) {
        jdbcTemplate.update("""
            INSERT INTO payment_records (payment_no, order_id, order_no, user_id, channel, out_trade_no,
                amount, status, created_at)
            VALUES (?, ?, ?, ?, 'balance', ?, ?, 'PENDING', ?)
            """, paymentNo, orderId, orderNo, userId, paymentNo, amount, OffsetDateTime.now());
    }

    // ---------------------------------------------------------------- 查询辅助

    public BigDecimal userBalance(long userId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM users WHERE id = ?", BigDecimal.class, userId);
    }

    public Integer goodsStock(long goodsId) {
        return jdbcTemplate.queryForObject("SELECT stock_count FROM goods WHERE id = ?", Integer.class, goodsId);
    }

    public int countCardsByStatus(String status) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM cards WHERE status = ?", Integer.class, status);
    }

    public int countCards() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cards", Integer.class);
    }

    public int countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `" + table + "`", Integer.class);
    }

    public List<Map<String, Object>> query(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, args);
    }
}
