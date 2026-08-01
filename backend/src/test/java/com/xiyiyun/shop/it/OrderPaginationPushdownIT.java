package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.it.support.ItFixtures;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.mvp.PageSlice;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 批次8C：订单列表分页下推到 SQL 后的行为守卫。
 *
 * <h2>这批用例在防什么</h2>
 * 改动前是「{@code SELECT} 全表 → Java 里 filter/sort → 控制层 {@code subList}」，
 * 改动后是「{@code WHERE} + {@code LIMIT/OFFSET} + {@code COUNT(*)}」。
 * 两套实现要对外等价，而等价性有几处很容易悄悄破掉、且线上只会表现为"搜不到"而不是报错：
 * <ol>
 *   <li>{@code deliveryMessage} 不是原样存的列，它在 {@code OrderPersistenceMapper} 里按状态回落。
 *       SQL 侧必须用 {@code CASE} 复刻同一套回落，否则按"订单已完成"搜索会漏掉
 *       库里 {@code delivery_message} 为空的已完成单；</li>
 *   <li>关键字来自用户输入，{@code %} 与 {@code _} 是 LIKE 元字符。不转义则搜 "50%"
 *       退化成通配匹配，把不相关订单也捞出来；</li>
 *   <li>{@code total} 必须来自与取数相同的 {@code WHERE}，否则出现"总数 41 但翻到末页是空的"；</li>
 *   <li>买家侧必须由 SQL 按 {@code user_id} 过滤。这条同时是<b>越权守卫</b>：
 *       一旦有人把过滤条件写回 Java 层而漏了某个分支，买家就会看到别人的订单。</li>
 * </ol>
 *
 * <h2>为什么必须是集成测试</h2>
 * 上述四点全部落在真实 SQL 的语义里（LIKE 转义、CASE 回落、COUNT 与 WHERE 一致性、
 * 排序键稳定性）。用内存 Map 兜底路径测等于什么都没测 —— 那条路径根本不执行 SQL。
 */
@DisplayName("批次8C：订单分页下推")
class OrderPaginationPushdownIT extends AbstractIntegrationTest {

    private static final long DIRECT_GOODS_ID = 8101L;
    private static final BigDecimal PRICE = new BigDecimal("10.00");

    private void seedBaseData() {
        fixtures.insertUserGroup();
        fixtures.insertCategory();
        fixtures.insertUser(new BigDecimal("1000.00"));
        fixtures.insertGoods(DIRECT_GOODS_ID, "IT分页商品", "DIRECT", 999, PRICE, null);
    }

    /**
     * 造若干订单并<b>拉开 created_at</b>。
     *
     * <p>排序键是 {@code (created_at DESC, id DESC)}；如果所有订单落在同一秒，
     * 用例就只能验证 id 兜底，验不到主排序键。这里让序号越大的订单越新。
     */
    private void seedOrders(int count) {
        for (int i = 1; i <= count; i++) {
            String orderNo = String.format("IT8C%03d", i);
            fixtures.insertOrder(orderNo, ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "PAID", 1, PRICE);
            jdbcTemplate.update(
                "UPDATE orders SET created_at = ? WHERE order_no = ?",
                OffsetDateTime.now().minusMinutes(count - i), orderNo
            );
        }
    }

    private List<String> orderNos(PageSlice<OrderItem> slice) {
        return slice.items().stream().map(OrderItem::orderNo).toList();
    }

    @Test
    @DisplayName("翻页不重不漏，且 total 是满足条件的总数而非本页条数")
    void pagingMustCoverEveryRowExactlyOnce() {
        seedBaseData();
        seedOrders(25);

        PageSlice<OrderItem> first = repository.pageOrders(null, null, null, null, 10, 0);
        PageSlice<OrderItem> second = repository.pageOrders(null, null, null, null, 10, 10);
        PageSlice<OrderItem> third = repository.pageOrders(null, null, null, null, 10, 20);

        assertThat(first.total()).as("total 必须是全部命中行数").isEqualTo(25L);
        assertThat(second.total()).isEqualTo(25L);
        assertThat(third.total()).isEqualTo(25L);
        assertThat(first.items()).hasSize(10);
        assertThat(second.items()).hasSize(10);
        assertThat(third.items()).as("末页只剩 5 条").hasSize(5);

        List<String> all = new java.util.ArrayList<>();
        all.addAll(orderNos(first));
        all.addAll(orderNos(second));
        all.addAll(orderNos(third));
        assertThat(all).as("三页拼起来应恰好覆盖 25 单，无重复无遗漏").hasSize(25).doesNotHaveDuplicates();

        // created_at 倒序：最后造的 IT8C025 最新，应在第一页第一条
        assertThat(first.items().get(0).orderNo()).isEqualTo("IT8C025");
        assertThat(third.items().get(4).orderNo()).isEqualTo("IT8C001");
    }

    @Test
    @DisplayName("越界页返回空数据但 total 照实返回，前端才能把页码收回有效范围")
    void offsetBeyondTotalMustStillReportTotal() {
        seedBaseData();
        seedOrders(3);

        PageSlice<OrderItem> slice = repository.pageOrders(null, null, null, null, 10, 100);

        assertThat(slice.items()).isEmpty();
        assertThat(slice.total()).isEqualTo(3L);
    }

    @Test
    @DisplayName("状态筛选在 SQL 里生效，total 随之收窄")
    void statusFilterMustNarrowBothItemsAndTotal() {
        seedBaseData();
        fixtures.insertOrder("IT8CS01", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "PAID", 1, PRICE);
        fixtures.insertOrder("IT8CS02", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "DELIVERED", 1, PRICE);
        fixtures.insertOrder("IT8CS03", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "DELIVERED", 1, PRICE);

        PageSlice<OrderItem> delivered = repository.pageOrders(null, "DELIVERED", null, null, 10, 0);

        assertThat(delivered.total()).isEqualTo(2L);
        assertThat(orderNos(delivered)).containsExactlyInAnyOrder("IT8CS02", "IT8CS03");
    }

    @Test
    @DisplayName("状态筛选大小写不敏感，与原内存实现的 toLowerCase 归一一致")
    void statusFilterMustBeCaseInsensitive() {
        seedBaseData();
        fixtures.insertOrder("IT8CC01", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "DELIVERED", 1, PRICE);

        assertThat(repository.pageOrders(null, "delivered", null, null, 10, 0).total()).isEqualTo(1L);
        assertThat(repository.pageOrders(null, "  DeLiVeReD  ", null, null, 10, 0).total())
            .as("首尾空白也要被 trim 掉")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("关键字命中订单号")
    void keywordMustMatchOrderNo() {
        seedBaseData();
        fixtures.insertOrder("IT8CK01", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "PAID", 1, PRICE);
        fixtures.insertOrder("IT8CK02", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "PAID", 1, PRICE);

        PageSlice<OrderItem> slice = repository.pageOrders("IT8CK01", null, null, null, 10, 0);

        assertThat(slice.total()).isEqualTo(1L);
        assertThat(orderNos(slice)).containsExactly("IT8CK01");
    }

    @Test
    @DisplayName("关键字命中充值账号（买家可搜自己的充值号）")
    void keywordMustMatchRechargeAccount() {
        seedBaseData();
        fixtures.insertOrder("IT8CR01", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "PAID", 1, PRICE);
        fixtures.insertOrder("IT8CR02", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "PAID", 1, PRICE);
        jdbcTemplate.update("UPDATE orders SET recharge_account = ? WHERE order_no = ?", "13800001111", "IT8CR01");
        jdbcTemplate.update("UPDATE orders SET recharge_account = ? WHERE order_no = ?", "13900002222", "IT8CR02");

        PageSlice<OrderItem> slice = repository.pageOrders("13800001111", null, null, null, 10, 0);

        assertThat(slice.total()).isEqualTo(1L);
        assertThat(orderNos(slice)).containsExactly("IT8CR01");
    }

    /**
     * 这条是 8C 最容易写错的地方。
     *
     * <p>{@code delivery_message} 留空的 DELIVERED 订单，对外展示的 {@code deliveryMessage}
     * 是回落出来的"订单已完成"。原 Java 过滤匹配的是回落<b>之后</b>的值，
     * 所以搜"订单已完成"能命中它。若 SQL 只写 {@code delivery_message LIKE ...}，
     * 这批订单就搜不到 —— 而线上恰恰绝大多数完成单都是这种（消息列为空）。
     */
    @Test
    @DisplayName("关键字要能命中派生出来的 deliveryMessage，而非只匹配库里的列值")
    void keywordMustMatchDerivedDeliveryMessage() {
        seedBaseData();
        fixtures.insertOrder("IT8CD01", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "DELIVERED", 1, PRICE);
        fixtures.insertOrder("IT8CD02", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "PAID", 1, PRICE);
        // 明确把 DELIVERED 单的消息列清空，模拟线上最常见的情形
        jdbcTemplate.update("UPDATE orders SET delivery_message = NULL WHERE order_no = ?", "IT8CD01");

        assertThat(repository.findOrder("IT8CD01"))
            .as("前置条件：对外展示值确实是回落出来的")
            .get()
            .extracting(OrderItem::deliveryMessage)
            .isEqualTo("订单已完成");

        PageSlice<OrderItem> slice = repository.pageOrders("订单已完成", null, null, null, 10, 0);

        assertThat(orderNos(slice))
            .as("库里消息列为空的已完成单，也必须能被『订单已完成』搜到")
            .containsExactly("IT8CD01");
        assertThat(slice.total()).isEqualTo(1L);
    }

    @Test
    @DisplayName("库里存了消息时优先用存的那份，不被回落覆盖")
    void keywordMustMatchStoredDeliveryMessageWhenPresent() {
        seedBaseData();
        fixtures.insertOrder("IT8CD03", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "DELIVERED", 1, PRICE);
        jdbcTemplate.update("UPDATE orders SET delivery_message = ? WHERE order_no = ?", "上游返回充值成功", "IT8CD03");

        assertThat(orderNos(repository.pageOrders("上游返回充值成功", null, null, null, 10, 0)))
            .containsExactly("IT8CD03");
    }

    /**
     * LIKE 元字符转义守卫。
     *
     * <p>{@code %} 不转义时，"50%" 会被 MySQL 当成"以 50 开头"的通配模式，
     * 把 IT8CE02（备注里是 "50off"）之类不相关的订单一起捞出来。
     * 这类缺陷不会报错，只会让管理员搜出一堆噪音，很难被发现。
     */
    @Test
    @DisplayName("关键字里的 % 必须按字面量匹配，不能当通配符")
    void percentInKeywordMustBeEscaped() {
        seedBaseData();
        fixtures.insertOrder("IT8CE01", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "PAID", 1, PRICE);
        fixtures.insertOrder("IT8CE02", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "PAID", 1, PRICE);
        jdbcTemplate.update("UPDATE orders SET delivery_message = ? WHERE order_no = ?", "折扣50%生效", "IT8CE01");
        jdbcTemplate.update("UPDATE orders SET delivery_message = ? WHERE order_no = ?", "折扣50off生效", "IT8CE02");

        PageSlice<OrderItem> slice = repository.pageOrders("50%", null, null, null, 10, 0);

        assertThat(orderNos(slice)).as("只应命中真的含 50% 的那单").containsExactly("IT8CE01");
        assertThat(slice.total()).isEqualTo(1L);
    }

    @Test
    @DisplayName("关键字里的下划线必须按字面量匹配")
    void underscoreInKeywordMustBeEscaped() {
        seedBaseData();
        fixtures.insertOrder("IT8CU01", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "PAID", 1, PRICE);
        fixtures.insertOrder("IT8CU02", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "PAID", 1, PRICE);
        jdbcTemplate.update("UPDATE orders SET request_id = ? WHERE order_no = ?", "req_1", "IT8CU01");
        jdbcTemplate.update("UPDATE orders SET request_id = ? WHERE order_no = ?", "reqX1", "IT8CU02");

        PageSlice<OrderItem> slice = repository.pageOrders("req_1", null, null, null, 10, 0);

        assertThat(orderNos(slice)).as("_ 不能匹配任意单字符").containsExactly("IT8CU01");
    }

    @Test
    @DisplayName("空白关键字视为不筛选，而不是搜索空串")
    void blankKeywordMustNotFilter() {
        seedBaseData();
        seedOrders(4);

        assertThat(repository.pageOrders("   ", null, null, null, 10, 0).total()).isEqualTo(4L);
        assertThat(repository.pageOrders(null, null, null, null, 10, 0).total()).isEqualTo(4L);
    }

    /**
     * 买家侧过滤 + 越权守卫。
     *
     * <p>{@code userId} 必须进 SQL 的 {@code WHERE}。这里刻意造第二个买家的订单，
     * 一旦过滤条件失效，用例会直接看到别人的订单号。
     */
    @Test
    @DisplayName("买家分页只能看到自己的订单，且 total 也只算自己的")
    void userScopedPagingMustNotLeakOtherBuyersOrders() {
        seedBaseData();
        long otherUserId = fixtures.insertUser(9002L, "13700003333", new BigDecimal("500.00"));
        fixtures.insertOrder("IT8CO01", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "PAID", 1, PRICE);
        fixtures.insertOrder("IT8CO02", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "PAID", 1, PRICE);
        fixtures.insertOrder("IT8CO03", otherUserId, DIRECT_GOODS_ID, "DIRECT", "PAID", 1, PRICE);

        PageSlice<OrderItem> mine = repository.pageOrders(null, null, null, ItFixtures.USER_ID, 10, 0);

        assertThat(mine.total()).as("total 不能把别人的订单算进来").isEqualTo(2L);
        assertThat(orderNos(mine)).containsExactlyInAnyOrder("IT8CO01", "IT8CO02");

        PageSlice<OrderItem> theirs = repository.pageOrders(null, null, null, otherUserId, 10, 0);
        assertThat(orderNos(theirs)).containsExactly("IT8CO03");
    }

    @Test
    @DisplayName("按 requestId 查单走唯一索引，且不能跨用户命中")
    void findByRequestIdMustBeUserScoped() {
        seedBaseData();
        long otherUserId = fixtures.insertUser(9003L, "13600004444", new BigDecimal("500.00"));
        fixtures.insertOrder("IT8CQ01", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "PAID", 1, PRICE);
        fixtures.insertOrder("IT8CQ02", otherUserId, DIRECT_GOODS_ID, "DIRECT", "PAID", 1, PRICE);
        // 同一个 requestId 落在两个不同买家名下：uk_orders_user_request 是 (user_id, request_id)，允许这种情形
        jdbcTemplate.update("UPDATE orders SET request_id = ? WHERE order_no = ?", "shared-req-1", "IT8CQ01");
        jdbcTemplate.update("UPDATE orders SET request_id = ? WHERE order_no = ?", "shared-req-1", "IT8CQ02");

        assertThat(repository.findOrderByRequestId(ItFixtures.USER_ID, "shared-req-1"))
            .get()
            .extracting(OrderItem::orderNo)
            .isEqualTo("IT8CQ01");
        assertThat(repository.findOrderByRequestId(otherUserId, "shared-req-1"))
            .as("必须按 user_id 隔离，不能串到别人的单")
            .get()
            .extracting(OrderItem::orderNo)
            .isEqualTo("IT8CQ02");
        assertThat(repository.findOrderByRequestId(ItFixtures.USER_ID, "no-such-req")).isEmpty();
    }

    /**
     * 分页不得再触发写副作用。
     *
     * <p>原实现在每个列表读里调 {@code expireStaleUnpaidOrders()}，读接口顺手改数据。
     * 批次8C 删掉了它（超时关单由 {@code OrderCompensationWorker} 每 60s 对着库做）。
     * 这里用一个已超时的未付款订单确认：翻页不会把它的状态改掉。
     * 若哪天有人为了"顺手"把 expire 加回读路径，本用例会红。
     */
    @Test
    @DisplayName("分页查询是纯读，不得顺手修改订单状态")
    void pagingMustNotMutateOrderStatus() {
        seedBaseData();
        fixtures.insertOrder("IT8CX01", ItFixtures.USER_ID, DIRECT_GOODS_ID, "DIRECT", "UNPAID", 1, PRICE);
        jdbcTemplate.update(
            "UPDATE orders SET created_at = ? WHERE order_no = ?",
            OffsetDateTime.now().minusDays(3), "IT8CX01"
        );

        repository.pageOrders(null, null, null, null, 10, 0);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM orders WHERE order_no = ?", String.class, "IT8CX01"))
            .as("读路径不得改状态")
            .isEqualTo("UNPAID");
    }
}
