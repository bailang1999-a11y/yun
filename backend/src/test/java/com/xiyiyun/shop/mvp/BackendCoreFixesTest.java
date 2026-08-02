package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BackendCoreFixesTest {
    @Test
    void inMemoryOrderPagingAppliesCreatedFromInclusively() {
        InMemoryShopRepository repository = newRepository();
        orders(repository).clear();
        OffsetDateTime boundary = OffsetDateTime.now().minusHours(1);
        OrderItem oldOrder = repository.createOrder(
            new CreateOrderRequest(10003L, 1, "13800000001", "", "time-old", "h5"), 90001L, "", "h5"
        );
        OrderItem boundaryOrder = repository.createOrder(
            new CreateOrderRequest(10003L, 1, "13800000001", "", "time-boundary", "h5"), 90001L, "", "h5"
        );
        orders(repository).put(oldOrder.orderNo(), withCreatedAt(oldOrder, boundary.minusSeconds(1)));
        orders(repository).put(boundaryOrder.orderNo(), withCreatedAt(boundaryOrder, boundary));

        PageSlice<OrderItem> slice = repository.pageOrders(null, null, null, boundary, null, 10, 0);

        assertThat(slice.total()).isEqualTo(1L);
        assertThat(slice.items()).extracting(OrderItem::orderNo).containsExactly(boundaryOrder.orderNo());
    }

    @Test
    void inMemoryOrderSummaryUsesAllFiltersAndNotOnlyOnePage() {
        InMemoryShopRepository repository = newRepository();
        orders(repository).clear();
        OffsetDateTime boundary = OffsetDateTime.now().minusHours(1);
        OrderItem oldOrder = repository.createOrder(
            new CreateOrderRequest(10003L, 1, "13800000001", "", "summary-old", "h5"), 90001L, "", "h5"
        );
        OrderItem delivered = repository.createOrder(
            new CreateOrderRequest(10003L, 1, "13800000001", "", "summary-delivered", "h5"), 90001L, "", "h5"
        );
        OrderItem active = repository.createOrder(
            new CreateOrderRequest(10003L, 1, "13800000001", "", "summary-active", "h5"), 90001L, "", "h5"
        );
        OrderItem failed = repository.createOrder(
            new CreateOrderRequest(10003L, 1, "13800000001", "", "summary-failed", "h5"), 90001L, "", "h5"
        );
        orders(repository).put(oldOrder.orderNo(), withSummaryFields(
            oldOrder, OrderStatus.DELIVERED, boundary.minusSeconds(1), new BigDecimal("9.00")
        ));
        orders(repository).put(delivered.orderNo(), withSummaryFields(
            delivered, OrderStatus.DELIVERED, boundary, new BigDecimal("10.50")
        ));
        orders(repository).put(active.orderNo(), withSummaryFields(
            active, OrderStatus.PROCURING, boundary.plusSeconds(1), null
        ));
        orders(repository).put(failed.orderNo(), withSummaryFields(
            failed, OrderStatus.FAILED, boundary.plusSeconds(2), new BigDecimal("3.25")
        ));

        OrderSummaryItem summary = repository.summarizeOrders(null, null, null, boundary, null);

        assertThat(summary.total()).isEqualTo(3);
        assertThat(summary.externalAmount()).isEqualByComparingTo("13.75");
        assertThat(summary.missingExternalAmountCount()).isEqualTo(1);
        assertThat(summary.activeCount()).isEqualTo(1);
        assertThat(summary.deliveredCount()).isEqualTo(1);
        assertThat(summary.failedCount()).isEqualTo(1);

        OrderSummaryItem filtered = repository.summarizeOrders(
            active.orderNo(), "procuring", active.goodsType().name().toLowerCase(), boundary, null
        );
        assertThat(filtered).isEqualTo(new OrderSummaryItem(1, BigDecimal.ZERO, 1, 1, 0, 0));
    }

    @Test
    void categoryReorderRequiresEverySiblingAndPersistsRequestedOrder() {
        InMemoryShopRepository repository = newRepository();

        assertThatThrownBy(() -> repository.reorderCategories(new ReorderCategoriesRequest(List.of(3L, 1L))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("all sibling category ids are required");

        repository.reorderCategories(new ReorderCategoriesRequest(List.of(3L, 1L, 2L)));

        assertThat(repository.listCategories().stream()
            .filter(item -> item.parentId() == null || item.parentId() == 0L)
            .map(CategoryItem::id))
            .containsExactly(3L, 1L, 2L);
    }

    @Test
    void loginRejectsUserWithoutPasswordHash() {
        InMemoryShopRepository repository = newRepository();

        assertThatThrownBy(() -> repository.loginUser(new LoginRequest(
            "13800000001", "any-password", "", "h5", "", "", ""
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("账号或密码不正确");
    }

    @Test
    void memberOrderDebitsBalanceExactlyOnceAndIsIdempotent() {
        InMemoryShopRepository repository = newRepository();
        GoodsItem original = goods(repository).get(10003L);
        goods(repository).put(10003L, withSaleSettings(original, original.price(), original.priceMode(), 1, 50, List.of("h5", "web", "api", "private")));
        BigDecimal initialBalance = users(repository).get(90001L).balance();
        CreateOrderRequest request = new CreateOrderRequest(10003L, 1, "13800000001", "member order", "member-idempotent-1");

        OrderItem first = repository.createMemberOrder(request, 90001L);
        OrderItem repeated = repository.createMemberOrder(request, 90001L);

        assertThat(repeated.orderNo()).isEqualTo(first.orderNo());
        assertThat(users(repository).get(90001L).balance())
            .isEqualByComparingTo(initialBalance.subtract(first.payAmount()));
    }

    @Test
    void requestIdRejectsDifferentOrderParameters() {
        InMemoryShopRepository repository = newRepository();
        CreateOrderRequest first = new CreateOrderRequest(10003L, 1, "13800000001", "first", "same-request", "h5");
        CreateOrderRequest different = new CreateOrderRequest(10003L, 2, "13800000001", "first", "same-request", "h5");

        repository.createOrder(first, 90001L, "127.0.0.1", "h5");

        assertThatThrownBy(() -> repository.createOrder(different, 90001L, "127.0.0.1", "h5"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("requestId already used with different order parameters");
    }

    @Test
    void orderEnforcesGroupRulesMaxBuyAndStock() {
        InMemoryShopRepository repository = newRepository();

        assertThat(repository.findGoods(10003L, 2L, false)).isEmpty();
        assertThatThrownBy(() -> repository.createOrder(
            new CreateOrderRequest(10003L, 1, "13800000002", "", "group-denied", "h5"),
            90002L,
            "",
            "h5"
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("当前会员组不可购买该商品。");

        GoodsItem original = goods(repository).get(10003L);
        goods(repository).put(10003L, withSaleSettings(original, original.price(), original.priceMode(), 2, 1, original.availablePlatforms()));
        assertThatThrownBy(() -> repository.createOrder(
            new CreateOrderRequest(10003L, 3, "13800000001", "", "over-max", "h5"), 90001L, "", "h5"
        )).hasMessage("quantity exceeds goods maxBuy");
        assertThatThrownBy(() -> repository.createOrder(
            new CreateOrderRequest(10003L, 2, "13800000001", "", "over-stock", "h5"), 90001L, "", "h5"
        )).hasMessage("goods stock is insufficient");
    }

    @Test
    void balanceRefundRestoresBalanceOnce() {
        InMemoryShopRepository repository = newRepository();
        BigDecimal initialBalance = users(repository).get(90001L).balance();
        OrderItem order = repository.createOrder(
            new CreateOrderRequest(10003L, 1, "13800000001", "", "refund-balance", "h5"), 90001L, "", "h5"
        );
        OrderItem paid = repository.payOrder(order.orderNo(), 90001L, new PayOrderRequest("balance", "h5"));

        repository.refundOrder(paid.orderNo());
        repository.refundOrder(paid.orderNo());

        assertThat(users(repository).get(90001L).balance()).isEqualByComparingTo(initialBalance);
        assertThat(repository.listRefunds()).filteredOn(item -> item.orderNo().equals(paid.orderNo())).hasSize(1);
    }

    @Test
    void externalPaymentCannotBeMarkedRefundedWithoutGateway() {
        InMemoryShopRepository repository = newRepository();
        OffsetDateTime now = OffsetDateTime.now();
        OrderItem order = new OrderItem(
            "EXTERNAL-REFUND", 90001L, "13800000001", 10003L, "manual", GoodsType.MANUAL, "h5", 1,
            BigDecimal.TEN, BigDecimal.TEN, OrderStatus.WAITING_MANUAL, "", "", "external-refund", "PAY-EXTERNAL",
            "wechat", List.of(), List.of(), "paid", now, now, null
        );
        orders(repository).put(order.orderNo(), order);
        payments(repository).put("PAY-EXTERNAL", new PaymentItem(
            "PAY-EXTERNAL", order.orderNo(), 90001L, "wechat", BigDecimal.TEN, "SUCCESS", "WX-1", now, now
        ));

        assertThatThrownBy(() -> repository.refundOrder(order.orderNo()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("外部支付退款网关尚未接入，不能标记退款成功");
        assertThat(orders(repository).get(order.orderNo()).status()).isEqualTo(OrderStatus.WAITING_MANUAL);
    }

    @Test
    void dynamicMemberPriceIsUsedForListDetailAndOrder() {
        InMemoryShopRepository repository = newRepository();
        GoodsItem original = goods(repository).get(10003L);
        goods(repository).put(10003L, withSaleSettings(original, BigDecimal.TEN, "DYNAMIC", 1, 50, original.availablePlatforms()));

        GoodsItem listed = repository.listGoods(null, "资料人工", "h5", 1L, false).getFirst();
        GoodsItem detailed = repository.findGoods(10003L, 1L, false).orElseThrow();
        OrderItem order = repository.createOrder(
            new CreateOrderRequest(10003L, 1, "13800000001", "", "dynamic-price", "h5"), 90001L, "", "h5"
        );

        assertThat(listed.price()).isEqualByComparingTo("11.00");
        assertThat(detailed.price()).isEqualByComparingTo("11.00");
        assertThat(order.unitPrice()).isEqualByComparingTo("11.00");
        assertThat(order.payAmount()).isEqualByComparingTo("11.00");
    }

    @Test
    void multiRechargeFieldsAreValidatedAgainstGoodsConfiguration() {
        InMemoryShopRepository repository = newRepository();

        // 多充值字段是"任填一项"（OR），不是"全部必填"（AND）：
        // 商品配了 mobile + game_uid，只填 mobile 也应当下单成功。
        OrderItem partial = repository.createOrder(
            new CreateOrderRequest(
                10002L, 1, "", "", "only-mobile", "h5", Map.of("mobile", "13800000001")
            ),
            90001L,
            "",
            "h5"
        );
        assertThat(partial.rechargeAccount()).isEqualTo("13800000001");

        // 一项都没填仍然要拦住，否则等于没有校验。
        assertThatThrownBy(() -> repository.createOrder(
            new CreateOrderRequest(
                10002L, 1, "", "", "all-blank", "h5", Map.of("mobile", "", "game_uid", "")
            ),
            90001L,
            "",
            "h5"
        )).hasMessage("请先填写充值账号");

        // 提交了商品未配置的字段码，仍应拒绝。
        assertThatThrownBy(() -> repository.createOrder(
            new CreateOrderRequest(
                10002L, 1, "", "", "unknown-code", "h5", Map.of("qq", "12345")
            ),
            90001L,
            "",
            "h5"
        )).hasMessage("充值字段与商品配置不匹配");

        assertThatThrownBy(() -> repository.createOrder(
            new CreateOrderRequest(
                10002L,
                1,
                "",
                "",
                "all-recharge-fields",
                "h5",
                Map.of("mobile", "13800000001", "game_uid", "player-123")
            ),
            90001L,
            "",
            "h5"
        )).hasMessage("充值账号字段冲突");
    }

    @Test
    void duplicateRechargeAliasesAreCanonicalizedByValueFormat() {
        InMemoryShopRepository repository = newRepository();

        OrderItem gameAccount = repository.createOrder(
            new CreateOrderRequest(
                10002L, 1, "", "", "duplicate-game-account", "h5",
                Map.of("mobile", "player-123", "game_uid", "player-123")
            ),
            90001L,
            "",
            "h5"
        );
        OrderItem genericGameAccount = repository.createOrder(
            new CreateOrderRequest(10002L, 1, "player-456", "", "generic-game-account", "h5"),
            90001L,
            "",
            "h5"
        );
        OrderItem misplacedGameAccount = repository.createOrder(
            new CreateOrderRequest(
                10002L, 1, "", "", "misplaced-game-account", "h5", Map.of("mobile", "player-789")
            ),
            90001L,
            "",
            "h5"
        );
        OrderItem mobileAccount = repository.createOrder(
            new CreateOrderRequest(
                10002L, 1, "", "", "duplicate-mobile-account", "h5",
                Map.of("mobile", "13800000001", "game_uid", "13800000001")
            ),
            90001L,
            "",
            "h5"
        );

        assertThat(gameAccount.rechargeAccount()).isEqualTo("player-123");
        assertThat(gameAccount.rechargeFields()).containsExactlyEntriesOf(Map.of("game_uid", "player-123"));
        assertThat(genericGameAccount.rechargeFields()).containsExactlyEntriesOf(Map.of("game_uid", "player-456"));
        assertThat(misplacedGameAccount.rechargeFields()).containsExactlyEntriesOf(Map.of("game_uid", "player-789"));
        assertThat(mobileAccount.rechargeFields()).containsExactlyEntriesOf(Map.of("mobile", "13800000001"));
    }

    @Test
    void idempotentRetryComparesTheCanonicalRechargeAccountInsteadOfTheLegacyFieldMap() {
        InMemoryShopRepository repository = newRepository();
        CreateOrderRequest original = new CreateOrderRequest(
            10002L, 1, "", "", "legacy-field-shape", "h5", Map.of("game_uid", "player-123")
        );
        OrderItem created = repository.createOrder(original, 90001L, "", "h5");
        orders(repository).put(created.orderNo(), new OrderItem(
            created.orderNo(), created.userId(), created.buyerAccount(), created.goodsId(), created.goodsName(),
            created.goodsType(), created.platform(), created.orderIp(), created.orderIpLocation(), created.quantity(),
            created.unitPrice(), created.payAmount(), created.status(), created.rechargeAccount(), Map.of(),
            created.buyerRemark(), created.requestId(), created.paymentNo(), created.payMethod(),
            created.deliveryItems(), created.channelAttempts(), created.deliveryMessage(), created.createdAt(),
            created.paidAt(), created.deliveredAt(), created.upstreamOrderNo()
        ));

        OrderItem retried = repository.createOrder(
            new CreateOrderRequest(10002L, 1, "player-123", "", "legacy-field-shape", "h5"),
            90001L,
            "",
            "h5"
        );

        assertThat(retried.orderNo()).isEqualTo(created.orderNo());
    }

    /**
     * FREE（自由注册）模式下账号不含 {@code @}，会被写进 {@code users.mobile}（varchar(32)）。
     *
     * <p>MOBILE 模式有 11 位手机号正则、EMAIL 模式有邮箱正则，两者天然限制长度；
     * FREE 模式不校验任何格式，若不显式限长，超长账号会在落库时被 MySQL
     * 以 {@code Data too long} 拒绝，进而可能留下「内存有、库里没有」的会员。
     */
    @Test
    void freeRegistrationRejectsAccountLongerThanMobileColumn() {
        InMemoryShopRepository repository = newRepository();
        switchToFreeRegistration(repository);

        assertThatThrownBy(() -> repository.authenticateUser(
            registerRequest("a".repeat(33)), ""
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("32");
    }

    @Test
    void freeRegistrationAcceptsPlainUsername() {
        InMemoryShopRepository repository = newRepository();
        switchToFreeRegistration(repository);

        AuthSession<UserItem> session = repository.authenticateUser(registerRequest("free_user_01"), "");

        assertThat(session.profile().mobile()).isEqualTo("free_user_01");
        // 不含 @ 的账号只占 mobile 列，email 必须留空，否则会去撞 uk_users_email。
        assertThat(session.profile().email()).isEmpty();
    }

    /**
     * 敏感配置键判定回归。
     *
     * <p>动机是一次真实缺陷：Altcha 的 HMAC 密钥用了 {@code hmac_key} 这个键名，
     * 而判定函数当时只匹配 secret / private_key / password / token / api_key / access_key，
     * 一个都不命中，于是密钥被<b>明文写进公开列</b>（生产库已复现：明文长度 191、密文列为空）。
     * 该密钥是防止伪造 PoW 挑战的唯一凭据，明文落库等于验证码可被绕过。
     *
     * <p>同时锁住反向断言：{@code *_public_key} 与 Turnstile 的 {@code site_key}
     * 本就是要下发给前端的公开值，绝不能被误判成敏感项而加密——那样前端会拿到密文，
     * 验证码直接不可用。
     */
    @Test
    void treatsHmacKeyAsSensitiveButKeepsPublicKeysPlain() {
        InMemoryShopRepository repository = newRepository();

        assertThat(isSensitive(repository, "hmac_key")).isTrue();
        assertThat(isSensitive(repository, "app_secret_key")).isTrue();
        assertThat(isSensitive(repository, "app_private_key")).isTrue();

        assertThat(isSensitive(repository, "alipay_public_key")).isFalse();
        assertThat(isSensitive(repository, "site_key")).isFalse();
        assertThat(isSensitive(repository, "app_id")).isFalse();
    }

    private static boolean isSensitive(InMemoryShopRepository repository, String key) {
        try {
            java.lang.reflect.Method method =
                InMemoryShopRepository.class.getDeclaredMethod("isSensitiveConfigKey", String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(repository, key);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void switchToFreeRegistration(InMemoryShopRepository repository) {
        repository.updateSystemSetting(new UpdateSystemSettingRequest(
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, true, "FREE", null, null
        ));
    }

    private static UserAuthRequest registerRequest(String account) {
        return new UserAuthRequest(account, "", "", "", "h5", "", "", "", "register", "");
    }

    private static InMemoryShopRepository newRepository() {
        return new InMemoryShopRepository(
            mock(OrderRealtimeBroadcaster.class),
            "admin",
            "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq",
            "Admin"
        );
    }

    private static GoodsItem withSaleSettings(
        GoodsItem item,
        BigDecimal price,
        String priceMode,
        int maxBuy,
        int stock,
        List<String> availablePlatforms
    ) {
        return new GoodsItem(
            item.id(), item.categoryId(), item.categoryName(), item.goodsName(), item.name(), item.subTitle(), item.description(),
            item.benefitDurations(), item.benefitType(), item.benefitBrand(), item.priceLimited(), item.priceLimitText(),
            item.coverUrl(), item.detailImages(), item.detailBlocks(), item.integrations(), item.pollingEnabled(), item.monitoringEnabled(),
            item.type(), item.platform(), price, item.originalPrice(), maxBuy, item.requireRechargeAccount(), item.accountTypes(),
            "retail-default", priceMode, item.priceCoefficient(), item.priceFixedAdd(), stock, item.sales(), item.status(), item.tags(),
            item.createdAt(), item.updatedAt(), availablePlatforms, item.forbiddenPlatforms(), item.cardKindId()
        );
    }

    private static OrderItem withCreatedAt(OrderItem item, OffsetDateTime createdAt) {
        return new OrderItem(
            item.orderNo(), item.userId(), item.buyerAccount(), item.goodsId(), item.goodsName(), item.goodsType(),
            item.platform(), item.orderIp(), item.orderIpLocation(), item.quantity(), item.unitPrice(), item.payAmount(),
            item.status(), item.rechargeAccount(), item.rechargeFields(), item.buyerRemark(), item.requestId(),
            item.paymentNo(), item.payMethod(), item.deliveryItems(), item.channelAttempts(), item.deliveryMessage(),
            createdAt, item.paidAt(), item.deliveredAt(), item.upstreamOrderNo(), item.externalMaxAmount()
        );
    }

    private static OrderItem withSummaryFields(
        OrderItem item,
        OrderStatus status,
        OffsetDateTime createdAt,
        BigDecimal externalMaxAmount
    ) {
        return new OrderItem(
            item.orderNo(), item.userId(), item.buyerAccount(), item.goodsId(), item.goodsName(), item.goodsType(),
            item.platform(), item.orderIp(), item.orderIpLocation(), item.quantity(), item.unitPrice(), item.payAmount(),
            status, item.rechargeAccount(), item.rechargeFields(), item.buyerRemark(), item.requestId(),
            item.paymentNo(), item.payMethod(), item.deliveryItems(), item.channelAttempts(), item.deliveryMessage(),
            createdAt, item.paidAt(), item.deliveredAt(), item.upstreamOrderNo(), externalMaxAmount
        );
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> field(InMemoryShopRepository repository, String name) {
        try {
            Field field = InMemoryShopRepository.class.getDeclaredField(name);
            field.setAccessible(true);
            return (Map<K, V>) field.get(repository);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Map<Long, GoodsItem> goods(InMemoryShopRepository repository) {
        return catalogField(repository, "goods");
    }

    private static Map<Long, UserItem> users(InMemoryShopRepository repository) {
        return userField(repository, "users");
    }

    private static Map<String, OrderItem> orders(InMemoryShopRepository repository) {
        return field(repository, "orders");
    }

    private static Map<String, PaymentItem> payments(InMemoryShopRepository repository) {
        return field(repository, "payments");
    }

    /**
     * 批次7B / 任务B：goods 已随商品域搬到 {@link CatalogService}。
     * 这里只机械改反射目标（仓储字段 → catalogService 字段），断言逐字未变。
     */
    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> catalogField(InMemoryShopRepository repository, String name) {
        try {
            Field holder = InMemoryShopRepository.class.getDeclaredField("catalogService");
            holder.setAccessible(true);
            Object catalog = holder.get(repository);
            Field field = catalog.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (Map<K, V>) field.get(catalog);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    /**
     * 批次7C / 任务C1：users 已随会员域搬到 {@link UserService}。
     * 同 {@link #catalogField} 的镜像手法，只改反射目标，断言逐字未变。
     */
    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> userField(InMemoryShopRepository repository, String name) {
        try {
            Field holder = InMemoryShopRepository.class.getDeclaredField("userService");
            holder.setAccessible(true);
            Object userService = holder.get(repository);
            Field field = userService.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (Map<K, V>) field.get(userService);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

}
