package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.persistence.AuditPersistenceStore;
import com.xiyiyun.shop.persistence.CatalogPersistenceStore;
import com.xiyiyun.shop.persistence.ConfigPersistenceStore;
import com.xiyiyun.shop.persistence.PersistentOrderStore;
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
            "UNVERIFIED"
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
    void memberApiCredentialPersistenceEncryptsSecretSetting() {
        ConfigPersistenceStore configPersistenceStore = mock(ConfigPersistenceStore.class);
        when(configPersistenceStore.systemSettings()).thenReturn(Map.of());
        when(configPersistenceStore.encryptSecretForSetting("member-secret")).thenReturn(Map.of(
            "ciphertext", "ciphertext-value",
            "nonce", "nonce-value",
            "keyVersion", "v1",
            "hash", "hash-value"
        ));
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
            "UNVERIFIED"
        ));

        repository.saveMemberCredential(90001L, new MemberApiCredentialRequest(true, "member-key", "member-secret", false, List.of(), 1000));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(configPersistenceStore, atLeastOnce()).saveRuntimeSetting(eq("member.credential.90001"), payloadCaptor.capture());
        String finalPayload = payloadCaptor.getAllValues().get(payloadCaptor.getAllValues().size() - 1);
        assertThat(finalPayload).contains("\"appSecretCiphertext\":\"ciphertext-value\"");
        assertThat(finalPayload).contains("\"appSecretNonce\":\"nonce-value\"");
        assertThat(finalPayload).contains("\"appSecretMasked\":\"memb****cret\"");
        assertThat(finalPayload).doesNotContain("\"appSecret\":\"member-secret\"");
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
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return new InMemoryShopRepository(
            mock(OrderRealtimeBroadcaster.class),
            provider(mock(PersistentOrderStore.class)),
            provider(mock(CatalogPersistenceStore.class)),
            provider(mock(AuditPersistenceStore.class)),
            provider(configPersistenceStore),
            provider(mock(RedisSecurityStateStore.class)),
            environment,
            "admin",
            "$2y$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuuabcdefghi",
            "Admin"
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, UserItem> users(InMemoryShopRepository repository) {
        return field(repository, "users");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, OrderItem> orders(InMemoryShopRepository repository) {
        return field(repository, "orders");
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, CategoryItem> categories(InMemoryShopRepository repository) {
        return field(repository, "categories");
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, GoodsItem> goods(InMemoryShopRepository repository) {
        return field(repository, "goods");
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, MemberApiCredentialItem> memberCredentials(InMemoryShopRepository repository) {
        return field(repository, "memberCredentials");
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
}
