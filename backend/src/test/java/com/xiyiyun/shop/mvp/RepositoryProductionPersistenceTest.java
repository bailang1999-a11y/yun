package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.persistence.AuditPersistenceStore;
import com.xiyiyun.shop.persistence.CatalogPersistenceStore;
import com.xiyiyun.shop.persistence.ConfigPersistenceStore;
import com.xiyiyun.shop.persistence.ConfigTableStore;
import com.xiyiyun.shop.persistence.PersistentOrderStore;
import com.xiyiyun.shop.persistence.entity.MemberApiCredentialRecordEntity;
import com.xiyiyun.shop.persistence.entity.PaymentChannelRecordEntity;
import com.xiyiyun.shop.realtime.OrderRealtimeBroadcaster;
import com.xiyiyun.shop.security.RedisSecurityStateStore;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

class RepositoryProductionPersistenceTest {
    @Test
    void prodProfileRejectsMissingPersistenceStores() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new InMemoryShopRepository(
            mock(OrderRealtimeBroadcaster.class),
            missingProvider(PersistentOrderStore.class),
            missingProvider(CatalogPersistenceStore.class),
            missingProvider(AuditPersistenceStore.class),
            missingProvider(ConfigPersistenceStore.class),
            missingProvider(RedisSecurityStateStore.class),
            environment,
            "admin",
            "$2y$10$nj5upOsCRbbEPg1csaQlcOyosbleuZVG7BfL45uh81kG5FpDYWCIq",
            "Admin"
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("prod profile requires order, catalog, audit and config persistence stores");
    }

    @Test
    void prodProfileRejectsMockRechargeBalanceTopUp() {
        InMemoryShopRepository repository = prodRepository();
        users(repository).put(90001L, new UserItem(
            90001L,
            "",
            "13800000001",
            "",
            "Prod User",
            1L,
            "默认会员",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "NORMAL",
            OffsetDateTime.now(),
            null,
            "NONE",
            "",
            "",
            "",
            "UNVERIFIED",
            null
        ));

        assertThatThrownBy(() -> repository.createRechargeRequest(90001L, new RechargeRequest(BigDecimal.TEN, "wechat", "")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("生产环境不允许模拟充值，请接入真实支付网关后再开放充值");
    }

    @Test
    void prodProfileRejectsMockPaymentForNonBalanceChannels() {
        InMemoryShopRepository repository = prodRepository();
        OffsetDateTime now = OffsetDateTime.now();
        paymentChannels(repository).put(20L, new PaymentChannelItem(
            20L,
            "wechat",
            "微信支付",
            "WECHAT",
            List.of("h5", "web"),
            "ENABLED",
            20,
            Map.of(),
            "",
            now,
            now
        ));
        orders(repository).put("ORDER-PROD-MOCK", new OrderItem(
            "ORDER-PROD-MOCK",
            90001L,
            "13800000001",
            10001L,
            "测试商品",
            GoodsType.CARD,
            "h5",
            1,
            BigDecimal.ONE,
            BigDecimal.ONE,
            OrderStatus.UNPAID,
            "",
            "",
            "request-prod-mock",
            "",
            "",
            List.of(),
            List.of(),
            "订单已创建，等待支付",
            now,
            null,
            null
        ));

        assertThatThrownBy(() -> repository.payOrder("ORDER-PROD-MOCK", 90001L, new PayOrderRequest("wechat", "h5")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("生产环境不允许模拟支付成功，请接入真实支付网关或使用余额支付");
    }

    @Test
    void prodProfileDoesNotSeedDemoCatalogOrOrders() {
        InMemoryShopRepository repository = prodRepository();

        assertThat(categories(repository)).isEmpty();
        assertThat(goods(repository)).isEmpty();
        assertThat(orders(repository)).isEmpty();
        assertThat(memberCredentials(repository)).isEmpty();
    }

    @Test
    void goodsListingReusesOneChannelSnapshotForAllItems() {
        CatalogPersistenceStore catalogPersistenceStore = mock(CatalogPersistenceStore.class);
        ConfigPersistenceStore configPersistenceStore = mock(ConfigPersistenceStore.class);
        when(configPersistenceStore.systemSettings()).thenReturn(Map.of());
        when(configPersistenceStore.listGoodsChannels()).thenReturn(List.of());
        when(catalogPersistenceStore.listGoods()).thenReturn(List.of(goodsItem(10001L), goodsItem(10002L)));
        InMemoryShopRepository repository = prodRepository(catalogPersistenceStore, configPersistenceStore);
        clearInvocations(configPersistenceStore);

        assertThat(repository.listGoods(null, "", "api", 1L, false)).hasSize(2);

        verify(configPersistenceStore, times(2)).listGoodsChannels();
    }

    @Test
    void memberApiCredentialPersistenceEncryptsSecretSetting() {
        ConfigPersistenceStore configPersistenceStore = mock(ConfigPersistenceStore.class);
        when(configPersistenceStore.systemSettings()).thenReturn(Map.of());
        when(configPersistenceStore.encryptSecretForSetting("member-secret")).thenReturn(Map.of(
            "ciphertext", "ciphertext-value",
            "nonce", "nonce-value",
            "keyVersion", "v1",
            "hash", "hash-value"
        ));
        ConfigTableStore configTableStore = mock(ConfigTableStore.class);
        when(configPersistenceStore.configTables()).thenReturn(configTableStore);
        InMemoryShopRepository repository = prodRepository(configPersistenceStore);
        users(repository).put(90001L, new UserItem(
            90001L,
            "",
            "13800000001",
            "",
            "Prod User",
            1L,
            "默认会员",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "NORMAL",
            OffsetDateTime.now(),
            null,
            "NONE",
            "",
            "",
            "",
            "UNVERIFIED",
            null
        ));

        repository.saveMemberCredential(90001L, new MemberApiCredentialRequest(
            true, "member-key", "member-secret", "https://member.example/callback", false, List.of(), 1000));

        // 批次7：凭据从 system_settings['member.credential.{id}'] 迁到 member_api_credentials 表。
        // 只改「写到哪」这一行，密文/掩码/不落明文这三条断言与迁移前逐字相同。
        ArgumentCaptor<MemberApiCredentialRecordEntity> rowCaptor =
            ArgumentCaptor.forClass(MemberApiCredentialRecordEntity.class);
        verify(configTableStore, atLeastOnce()).saveMemberCredential(rowCaptor.capture());
        MemberApiCredentialRecordEntity row = rowCaptor.getAllValues().get(rowCaptor.getAllValues().size() - 1);
        assertThat(row.getUserId()).isEqualTo(90001L);
        assertThat(row.getAppSecretCiphertext()).isEqualTo("ciphertext-value");
        assertThat(row.getAppSecretNonce()).isEqualTo("nonce-value");
        assertThat(row.getAppSecretMasked()).isEqualTo("memb****cret");
        assertThat(row.getAppSecretHash()).isEqualTo("hash-value");
        assertThat(row.getCallbackUrl()).isEqualTo("https://member.example/callback");
        // 原断言是「payload 里不出现明文 appSecret」。行表没有明文列，等价断言改成
        // 「落库的每个文本字段都不含明文」，强度不降。
        assertThat(List.of(
            String.valueOf(row.getAppKey()),
            String.valueOf(row.getAppSecretMasked()),
            String.valueOf(row.getAppSecretCiphertext()),
            String.valueOf(row.getAppSecretNonce()),
            String.valueOf(row.getAppSecretHash()),
            String.valueOf(row.getIpWhitelist())
        )).noneMatch(value -> value.contains("member-secret"));

        repository.saveMemberCredential(90001L, new MemberApiCredentialRequest(
            null, null, null, "  ", false, null, null));
        verify(configTableStore, atLeastOnce()).saveMemberCredential(rowCaptor.capture());
        MemberApiCredentialRecordEntity cleared = rowCaptor.getAllValues().get(rowCaptor.getAllValues().size() - 1);
        assertThat(cleared.getCallbackUrl()).isNull();
    }

    @Test
    void paymentChannelPersistenceEncryptsPrivateConfigValues() {
        ConfigPersistenceStore configPersistenceStore = mock(ConfigPersistenceStore.class);
        when(configPersistenceStore.systemSettings()).thenReturn(Map.of());
        when(configPersistenceStore.encryptSecretForSetting("payment-secret")).thenReturn(Map.of(
            "ciphertext", "payment-ciphertext",
            "nonce", "payment-nonce",
            "keyVersion", "v1",
            "hash", "payment-hash"
        ));
        when(configPersistenceStore.encryptSecretForSetting("payment-v3-key")).thenReturn(Map.of(
            "ciphertext", "payment-v3-ciphertext",
            "nonce", "payment-v3-nonce",
            "keyVersion", "v1",
            "hash", "payment-v3-hash"
        ));
        ConfigTableStore configTableStore = mock(ConfigTableStore.class);
        when(configPersistenceStore.configTables()).thenReturn(configTableStore);
        InMemoryShopRepository repository = prodRepository(configPersistenceStore);

        repository.createPaymentChannel(new PaymentChannelRequest(
            "secure-pay",
            "安全支付",
            "WECHAT",
            List.of("web"),
            "DISABLED",
            50,
            Map.of("app_id", "public-app", "private_key", "payment-secret", "api_v3_key", "payment-v3-key"),
            ""
        ));

        // 批次7：支付渠道从 system_settings['payment.channels'] 迁到 payment_channels 表。
        // 只改「写到哪」这一行；公开配置可见、密文可见、明文不落库三条断言逐字保留，
        // 断言对象由「整个 KV payload」换成「这一行落库的全部文本列拼起来」。
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PaymentChannelRecordEntity>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(configTableStore, atLeastOnce()).replacePaymentChannels(rowsCaptor.capture());
        List<PaymentChannelRecordEntity> rows = rowsCaptor.getAllValues().get(rowsCaptor.getAllValues().size() - 1);
        // 快照是整表重写，里面还有默认渠道；只挑本用例新建的那条来断言。
        PaymentChannelRecordEntity row = rows.stream()
            .filter(item -> "secure-pay".equals(item.getCode()))
            .findFirst()
            .orElseThrow();
        String finalPayload = String.join("|",
            String.valueOf(row.getCode()),
            String.valueOf(row.getName()),
            String.valueOf(row.getChannelType()),
            String.valueOf(row.getTerminals()),
            String.valueOf(row.getStatus()),
            String.valueOf(row.getConfigPublic()),
            String.valueOf(row.getConfigSecrets()),
            String.valueOf(row.getRemark()));
        assertThat(finalPayload).contains("\"app_id\":\"public-app\"");
        assertThat(finalPayload).contains("\"ciphertext\":\"payment-ciphertext\"");
        assertThat(finalPayload).doesNotContain("payment-secret");
        assertThat(finalPayload).doesNotContain("payment-v3-key");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> missingProvider(Class<T> type) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static InMemoryShopRepository prodRepository() {
        ConfigPersistenceStore configPersistenceStore = mock(ConfigPersistenceStore.class);
        when(configPersistenceStore.systemSettings()).thenReturn(Map.of());
        return prodRepository(configPersistenceStore);
    }

    private static InMemoryShopRepository prodRepository(ConfigPersistenceStore configPersistenceStore) {
        return prodRepository(mock(CatalogPersistenceStore.class), configPersistenceStore);
    }

    private static InMemoryShopRepository prodRepository(
        CatalogPersistenceStore catalogPersistenceStore,
        ConfigPersistenceStore configPersistenceStore
    ) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return new InMemoryShopRepository(
            mock(OrderRealtimeBroadcaster.class),
            provider(mock(PersistentOrderStore.class)),
            provider(catalogPersistenceStore),
            provider(mock(AuditPersistenceStore.class)),
            provider(configPersistenceStore),
            provider(mock(RedisSecurityStateStore.class)),
            environment,
            "admin",
            "$2y$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuuabcdefghi",
            "Admin"
        );
    }

    private static GoodsItem goodsItem(Long id) {
        OffsetDateTime now = OffsetDateTime.now();
        return new GoodsItem(
            id, 1L, "测试分类", "商品 " + id, "商品 " + id, "", "", List.of(), "", "", false, "", "",
            List.of(), List.of(), List.of(), false, false, GoodsType.CARD, "api", BigDecimal.ONE, BigDecimal.ONE,
            1, false, List.of(), "", "FIXED", BigDecimal.ONE, BigDecimal.ZERO, 1, 0, "ON_SALE", List.of(),
            now, now, List.of("api"), List.of(), null
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, UserItem> users(InMemoryShopRepository repository) {
        return userField(repository, "users");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, OrderItem> orders(InMemoryShopRepository repository) {
        return field(repository, "orders");
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, CategoryItem> categories(InMemoryShopRepository repository) {
        return catalogField(repository, "categories");
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, GoodsItem> goods(InMemoryShopRepository repository) {
        return catalogField(repository, "goods");
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, MemberApiCredentialItem> memberCredentials(InMemoryShopRepository repository) {
        return userField(repository, "memberCredentials");
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, PaymentChannelItem> paymentChannels(InMemoryShopRepository repository) {
        return field(repository, "paymentChannels");
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(InMemoryShopRepository repository, String name) {
        try {
            Field field = InMemoryShopRepository.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(repository);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    /**
     * 批次7B / 任务B：goods / categories 已随商品域搬到 {@link CatalogService}。
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
     * 批次7C / 任务C1：users / memberCredentials 已随会员域搬到 {@link UserService}。
     * 同 {@link #catalogField} 的镜像手法，只改反射目标，断言逐字未变。
     */
    @SuppressWarnings("unchecked")
    private static <T> T userField(InMemoryShopRepository repository, String name) {
        try {
            Field holder = InMemoryShopRepository.class.getDeclaredField("userService");
            holder.setAccessible(true);
            Object userService = holder.get(repository);
            Field field = userService.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(userService);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

}
