package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.persistence.AgisoPriceSubscriptionStore;
import com.xiyiyun.shop.persistence.entity.AgisoPriceSubscriptionEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgisoPriceSubscriptionServiceTest {
    private static final String APP_SECRET = "agiso-secret";
    private static final String MEMBER_SECRET = "member-secret";
    private static final String GUID = "2fe15520c6ac45ff83a270aaef71a873";

    private AgisoPriceSubscriptionStore store;
    private AgisoCallbackClient callbackClient;
    private OutboundProtocolService protocolService;
    private AgisoPriceSubscriptionService service;

    @BeforeEach
    void setUp() {
        store = mock(AgisoPriceSubscriptionStore.class);
        callbackClient = mock(AgisoCallbackClient.class);
        protocolService = mock(OutboundProtocolService.class);
        when(protocolService.callbackMemberSecret(90001L)).thenReturn(MEMBER_SECRET);
        when(protocolService.settings()).thenReturn(settings());
        service = new AgisoPriceSubscriptionService(store, callbackClient, protocolService, APP_SECRET);
    }

    @Test
    void subscriptionStoresTheCurrentMemberPriceAsBaselineWithoutPushing() {
        GoodsItem goods = goods(new BigDecimal("12.34"));
        OutboundApiPrincipal principal = principal();

        service.subscribe(principal, GUID, goods);

        verify(store).subscribe(
            eq(90001L),
            eq("90001"),
            eq(GUID),
            eq(10004L),
            eq(new BigDecimal("12.34")),
            anyLong(),
            any(OffsetDateTime.class)
        );
        verifyNoInteractions(callbackClient);
    }

    @Test
    void unchangedPriceOnlyAdvancesTheNextCheck() {
        AgisoPriceSubscriptionEntity subscription = subscription(new BigDecimal("12.34"), 100L);
        when(store.findDue(any(), anyInt())).thenReturn(List.of(subscription));
        when(store.claim(eq(1L), any(), any(), any())).thenReturn(true);
        when(protocolService.currentGoods(90001L, 10004L)).thenReturn(goods(new BigDecimal("12.3400")));

        service.dispatchDue();

        verify(store).markUnchanged(eq(1L), any(), any(OffsetDateTime.class));
        verifyNoInteractions(callbackClient);
        verify(store, never()).markNotified(anyLong(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void changedPricePushesTheDocumentedSignedPayloadAndAdvancesPriceVersion() {
        long previousVersion = System.currentTimeMillis() + 60_000L;
        AgisoPriceSubscriptionEntity subscription = subscription(new BigDecimal("12.34"), previousVersion);
        when(store.findDue(any(), anyInt())).thenReturn(List.of(subscription));
        when(store.claim(eq(1L), any(), any(), any())).thenReturn(true);
        when(protocolService.currentGoods(90001L, 10004L)).thenReturn(goods(new BigDecimal("15.67")));
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);

        service.dispatchDue();

        verify(callbackClient).post(
            eq("http://cb-acpr.agiso.com/SupplierProductNotify/" + GUID),
            payloadCaptor.capture(),
            eq(30)
        );
        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload)
            .containsEntry("supplierAccountGuid", GUID)
            .containsEntry("productNo", "10004")
            .containsEntry("productCost", new BigDecimal("15.67"));
        assertThat((Long) payload.get("priceVer")).isEqualTo(previousVersion + 1L);
        assertThat(payload.get("sign")).isEqualTo(AgisoSignatureUtil.sign(payload, APP_SECRET + MEMBER_SECRET));
        verify(store).markNotified(
            eq(1L),
            any(),
            eq(new BigDecimal("15.67")),
            eq(previousVersion + 1L),
            any(OffsetDateTime.class),
            any(OffsetDateTime.class)
        );
    }

    @Test
    void rejectedNotificationIsRetriedAndDoesNotAdvanceTheBaseline() {
        AgisoPriceSubscriptionEntity subscription = subscription(new BigDecimal("12.34"), 100L);
        when(store.findDue(any(), anyInt())).thenReturn(List.of(subscription));
        when(store.claim(eq(1L), any(), any(), any())).thenReturn(true);
        when(protocolService.currentGoods(90001L, 10004L)).thenReturn(goods(new BigDecimal("15.67")));
        doThrow(new IllegalStateException("agiso callback rejected: code=500"))
            .when(callbackClient).post(any(), any(), anyInt());

        service.dispatchDue();

        verify(store).retry(eq(1L), any(), any(OffsetDateTime.class), eq("agiso callback rejected: code=500"));
        verify(store, never()).markNotified(anyLong(), any(), any(), anyLong(), any(), any());
    }

    @Test
    void aSubscriptionThatCannotBeClaimedIsNotPushed() {
        AgisoPriceSubscriptionEntity subscription = subscription(new BigDecimal("12.34"), 100L);
        when(store.findDue(any(), anyInt())).thenReturn(List.of(subscription));
        when(store.claim(eq(1L), any(), any(), any())).thenReturn(false);

        service.dispatchDue();

        verifyNoInteractions(callbackClient);
        verify(protocolService, never()).currentGoods(anyLong(), anyLong());
    }

    @Test
    void rejectsSupplierGuidsThatCouldChangeTheOfficialCallbackPath() {
        assertThatThrownBy(() -> service.subscribe(principal(), "../../example", goods(BigDecimal.ONE)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("supplierAccountGuid is invalid");
    }

    private OutboundApiPrincipal principal() {
        UserItem user = new UserItem(
            90001L, "", "", "", "测试会员", 1L, "默认会员",
            BigDecimal.ZERO, BigDecimal.ZERO, "ENABLED", OffsetDateTime.now(), null,
            "", "", "", "", "", "tester"
        );
        MemberApiCredentialItem credential = new MemberApiCredentialItem(
            1L, 90001L, "member_90001", MEMBER_SECRET, "ENABLED", List.of(), 1000,
            OffsetDateTime.now(), null
        );
        return new OutboundApiPrincipal(user, credential);
    }

    private AgisoPriceSubscriptionEntity subscription(BigDecimal price, long priceVer) {
        AgisoPriceSubscriptionEntity entity = new AgisoPriceSubscriptionEntity();
        entity.setId(1L);
        entity.setUserId(90001L);
        entity.setPlatformUserId("member_90001");
        entity.setSupplierAccountGuid(GUID);
        entity.setProductNo(10004L);
        entity.setState("ACTIVE");
        entity.setLastNotifiedPrice(price);
        entity.setLastPriceVer(priceVer);
        entity.setAttemptCount(0);
        return entity;
    }

    private GoodsItem goods(BigDecimal price) {
        return new GoodsItem(
            10004L, 1L, "测试分类", "测试商品", "测试商品", "", "", List.of(),
            "", "", false, "", "", List.of(), List.of(), List.of(), false, false,
            GoodsType.DIRECT, "api", price, price, 99, false, List.of(), "", "FIXED",
            BigDecimal.ONE, BigDecimal.ZERO, 100, 0, "ON_SALE", List.of(), OffsetDateTime.now(),
            OffsetDateTime.now(), List.of("api"), List.of(), null
        );
    }

    private OutboundProtocolSettings settings() {
        return new OutboundProtocolSettings(
            true, "", 100, 30, Map.of(), "ALL", List.of(), List.of(),
            List.of("CARD", "DIRECT"), "MEMBER_GROUP", BigDecimal.ZERO, false
        );
    }
}
