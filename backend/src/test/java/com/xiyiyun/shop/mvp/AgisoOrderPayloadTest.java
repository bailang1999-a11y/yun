package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgisoOrderPayloadTest {
    private static final String APP_SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void onlyCompletedAndFailedOrdersAreCallbackReady() {
        assertThat(AgisoOrderPayload.callbackReady(order(OrderStatus.DELIVERED))).isTrue();
        assertThat(AgisoOrderPayload.callbackReady(order(OrderStatus.FAILED))).isTrue();
        assertThat(AgisoOrderPayload.callbackReady(order(OrderStatus.REFUNDED))).isTrue();
        assertThat(AgisoOrderPayload.callbackReady(order(OrderStatus.CANCELLED))).isTrue();
        assertThat(AgisoOrderPayload.callbackReady(order(OrderStatus.CLOSED))).isTrue();

        assertThat(AgisoOrderPayload.callbackReady(order(OrderStatus.PROCURING))).isFalse();
        assertThat(AgisoOrderPayload.callbackReady(order(OrderStatus.WAITING_MANUAL))).isFalse();
        assertThat(AgisoOrderPayload.callbackReady(order(OrderStatus.REFUNDING))).isFalse();
    }

    @Test
    void refundingRemainsProcessingUntilTheRefundIsFinal() {
        assertThat(AgisoOrderPayload.from(order(OrderStatus.REFUNDING), APP_SECRET))
            .containsEntry("orderStatus", 10)
            .containsEntry("failCode", 0)
            .containsEntry("failReason", "");
        assertThat(AgisoOrderPayload.from(order(OrderStatus.REFUNDED), APP_SECRET))
            .containsEntry("orderStatus", 30)
            .containsEntry("failCode", 1);
    }

    @Test
    void encryptsDeliveredCardsWithThe91KamiAes256EcbContract() {
        assertThat(AgisoOrderPayload.from(cardOrder(), APP_SECRET).get("cards"))
            .isEqualTo("GkDokyzdJd6M8QxK54ReLsyaUGA6NkGfxwSis8jH26A1k5nOhWMq8zcTijHoRePU"
                + "fmL7bDU69sP6YENxSbOXRQ==");
    }

    @Test
    void rejectsAnInvalidCardEncryptionKeyLength() {
        assertThatThrownBy(() -> AgisoOrderPayload.from(cardOrder(), "too-short"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("agiso app secret must be 32 UTF-8 bytes for card encryption");
    }

    private OrderItem order(OrderStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new OrderItem(
            "local-1", 90001L, "buyer", 10013L, "test", GoodsType.DIRECT, "api",
            1, BigDecimal.ONE, BigDecimal.ONE, status, "13800138000", "", "external-1",
            "payment-1", "balance", List.of(), List.of(), "status message",
            now, now, status == OrderStatus.DELIVERED ? now : null
        );
    }

    private OrderItem cardOrder() {
        OffsetDateTime now = OffsetDateTime.now();
        return new OrderItem(
            "local-1", 90001L, "buyer", 10005L, "test card", GoodsType.CARD, "api",
            1, BigDecimal.ONE, BigDecimal.ONE, OrderStatus.DELIVERED, "", "", "external-1",
            "payment-1", "balance", List.of("CARD-NO|CARD-PWD"), List.of(), "done",
            now, now, now
        );
    }
}
