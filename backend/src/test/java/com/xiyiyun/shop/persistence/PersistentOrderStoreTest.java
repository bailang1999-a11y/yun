package com.xiyiyun.shop.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.mvp.CardSecret;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.mvp.PaymentCallbackLogItem;
import com.xiyiyun.shop.mvp.PaymentItem;
import com.xiyiyun.shop.persistence.entity.CardRecordEntity;
import com.xiyiyun.shop.persistence.entity.OrderRecordEntity;
import com.xiyiyun.shop.persistence.entity.PaymentCallbackLogEntity;
import com.xiyiyun.shop.persistence.entity.PaymentRecordEntity;
import com.xiyiyun.shop.persistence.mapper.CardRecordMapper;
import com.xiyiyun.shop.persistence.mapper.OrderRecordMapper;
import com.xiyiyun.shop.persistence.mapper.PaymentCallbackLogMapper;
import com.xiyiyun.shop.persistence.mapper.PaymentRecordMapper;
import com.xiyiyun.shop.persistence.mapper.RefundRecordMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.mockito.ArgumentMatchers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PersistentOrderStoreTest {
    private final OrderRecordMapper orderRecordMapper = mock(OrderRecordMapper.class);
    private final PaymentRecordMapper paymentRecordMapper = mock(PaymentRecordMapper.class);
    private final PaymentCallbackLogMapper paymentCallbackLogMapper = mock(PaymentCallbackLogMapper.class);
    private final RefundRecordMapper refundRecordMapper = mock(RefundRecordMapper.class);
    private final CardRecordMapper cardRecordMapper = mock(CardRecordMapper.class);
    private final CardCipherService cardCipherService = new CardCipherService("test-card-secret");
    private final PersistentOrderStore store = new PersistentOrderStore(
        orderRecordMapper,
        paymentRecordMapper,
        paymentCallbackLogMapper,
        refundRecordMapper,
        cardRecordMapper,
        cardCipherService
    );

    @Test
    void saveOrderSnapshotUpsertsAndReloadsId() {
        when(orderRecordMapper.findIdByOrderNo("ORD-1")).thenReturn(101L);

        OrderRecordEntity saved = store.saveOrderSnapshot(order());

        ArgumentCaptor<OrderRecordEntity> captor = ArgumentCaptor.forClass(OrderRecordEntity.class);
        verify(orderRecordMapper).upsertByOrderNo(captor.capture());
        assertThat(captor.getValue().getOrderNo()).isEqualTo("ORD-1");
        assertThat(saved.getId()).isEqualTo(101L);
    }

    @Test
    void listOrdersMapsPersistedSnapshots() {
        when(orderRecordMapper.selectActiveSnapshots()).thenReturn(List.of(orderRecord("ORD-1")));
        when(paymentRecordMapper.findLatestByOrderNo("ORD-1")).thenReturn(paymentRecord("PAY-1", "balance"));

        List<OrderItem> orders = store.listOrders();

        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).orderNo()).isEqualTo("ORD-1");
        assertThat(orders.get(0).paymentNo()).isEqualTo("PAY-1");
        assertThat(orders.get(0).payMethod()).isEqualTo("balance");
    }

    @Test
    void findOrderMapsPersistedSnapshot() {
        when(orderRecordMapper.findByOrderNo("ORD-1")).thenReturn(orderRecord("ORD-1"));
        when(paymentRecordMapper.findLatestByOrderNo("ORD-1")).thenReturn(paymentRecord("PAY-1", "balance"));

        assertThat(store.findOrder("ORD-1")).get().extracting(OrderItem::paymentNo).isEqualTo("PAY-1");
    }

    @Test
    void savePaymentSnapshotResolvesOrderIdWhenMissing() {
        when(orderRecordMapper.findIdByOrderNo("ORD-1")).thenReturn(101L);

        PaymentRecordEntity saved = store.savePaymentSnapshot(payment(), null);

        ArgumentCaptor<PaymentRecordEntity> captor = ArgumentCaptor.forClass(PaymentRecordEntity.class);
        verify(paymentRecordMapper).upsertByPaymentNo(captor.capture());
        assertThat(captor.getValue().getOrderId()).isEqualTo(101L);
        assertThat(saved.getOrderId()).isEqualTo(101L);
    }

    @Test
    void savePaymentCallbackLogAppendsLog() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");

        store.savePaymentCallbackLog(new PaymentCallbackLogItem(
            1L,
            "mock",
            "PAY-1",
            "ORD-1",
            "SUCCESS",
            "trade-1",
            "SUCCESS",
            "accepted",
            now
        ));

        ArgumentCaptor<PaymentCallbackLogEntity> captor = ArgumentCaptor.forClass(PaymentCallbackLogEntity.class);
        verify(paymentCallbackLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getPaymentNo()).isEqualTo("PAY-1");
        assertThat(captor.getValue().getCallbackStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void markCardsSoldUpdatesEachCard() {
        CardRecordEntity first = card(1L);
        CardRecordEntity second = card(2L);
        OffsetDateTime soldAt = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");
        when(cardRecordMapper.markSold(any(), any(), any())).thenReturn(1);

        int updated = store.markCardsSold(List.of(first, second), 101L, soldAt);

        assertThat(updated).isEqualTo(2);
        verify(cardRecordMapper).markSold(1L, 101L, soldAt);
        verify(cardRecordMapper).markSold(2L, 101L, soldAt);
    }

    @Test
    void saveImportedCardEncryptsContentBeforePersisting() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");

        store.saveImportedCard(new CardSecret(
            1L,
            11L,
            "CARD-1",
            "mask",
            "plain-card-secret",
            "preview",
            "AVAILABLE",
            null,
            now,
            null
        ));

        ArgumentCaptor<CardRecordEntity> captor = ArgumentCaptor.forClass(CardRecordEntity.class);
        verify(cardRecordMapper).upsertImportedCard(captor.capture());
        CardRecordEntity saved = captor.getValue();
        assertThat(saved.getGoodsId()).isEqualTo(11L);
        assertThat(saved.getStatus()).isEqualTo("UNSOLD");
        assertThat(new String(saved.getCardCiphertext())).doesNotContain("plain-card-secret");
        assertThat(cardCipherService.decrypt(saved.getCardCiphertext(), saved.getCardNonce())).isEqualTo("plain-card-secret");
    }

    @Test
    void saveImportedCardSupportsCardKindBoundStock() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");

        store.saveImportedCard(new CardSecret(
            1L,
            null,
            "CARD-1",
            "mask",
            "kind-card-secret",
            "preview",
            "AVAILABLE",
            null,
            now,
            null,
            5L
        ));

        ArgumentCaptor<CardRecordEntity> captor = ArgumentCaptor.forClass(CardRecordEntity.class);
        verify(cardRecordMapper).upsertImportedCard(captor.capture());
        assertThat(captor.getValue().getGoodsId()).isNull();
        assertThat(captor.getValue().getCardKindId()).isEqualTo(5L);
    }

    @Test
    void deliverCardsForOrderLocksMarksSoldAndDecryptsCards() {
        when(orderRecordMapper.findIdByOrderNo("ORD-1")).thenReturn(101L);
        CardCipherService.EncryptedCard firstEncrypted = cardCipherService.encrypt("secret-a");
        CardCipherService.EncryptedCard secondEncrypted = cardCipherService.encrypt("secret-b");
        CardRecordEntity first = card(1L, firstEncrypted);
        CardRecordEntity second = card(2L, secondEncrypted);
        when(cardRecordMapper.lockAvailableCardsByGoodsId(11L, 2)).thenReturn(List.of(first, second));
        when(cardRecordMapper.markSold(any(), any(), any())).thenReturn(1);

        List<String> delivered = store.deliverCardsForOrder(order(2), null);

        assertThat(delivered).containsExactly("secret-a", "secret-b");
        verify(cardRecordMapper).lockAvailableCardsByGoodsId(11L, 2);
        verify(cardRecordMapper).markSold(ArgumentMatchers.eq(1L), ArgumentMatchers.eq(101L), ArgumentMatchers.any());
        verify(cardRecordMapper).markSold(ArgumentMatchers.eq(2L), ArgumentMatchers.eq(101L), ArgumentMatchers.any());
    }

    @Test
    void deliverCardsForOrderCanLockByCardKind() {
        when(orderRecordMapper.findIdByOrderNo("ORD-1")).thenReturn(101L);
        CardCipherService.EncryptedCard encrypted = cardCipherService.encrypt("shared-secret");
        CardRecordEntity card = card(1L, encrypted);
        when(cardRecordMapper.lockAvailableCardsByCardKindId(5L, 1)).thenReturn(List.of(card));
        when(cardRecordMapper.markSold(any(), any(), any())).thenReturn(1);

        List<String> delivered = store.deliverCardsForOrder(order(), 5L);

        assertThat(delivered).containsExactly("shared-secret");
        verify(cardRecordMapper).lockAvailableCardsByCardKindId(5L, 1);
    }

    private OrderItem order() {
        return order(1);
    }

    private OrderItem order(int quantity) {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");
        return new OrderItem(
            "ORD-1",
            9L,
            "buyer",
            11L,
            "Tencent Card",
            GoodsType.CARD,
            "H5",
            quantity,
            new BigDecimal("25.00"),
            new BigDecimal("25.00").multiply(BigDecimal.valueOf(quantity)),
            OrderStatus.UNPAID,
            null,
            null,
            "req-1",
            null,
            null,
            List.of(),
            List.of(),
            "pending",
            now,
            null,
            null
        );
    }

    private OrderRecordEntity orderRecord(String orderNo) {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");
        OrderRecordEntity record = new OrderRecordEntity();
        record.setOrderNo(orderNo);
        record.setUserId(9L);
        record.setGoodsId(11L);
        record.setGoodsName("Tencent Card");
        record.setGoodsType("CARD");
        record.setQuantity(1);
        record.setUnitPrice(new BigDecimal("25.00"));
        record.setPayAmount(new BigDecimal("25.00"));
        record.setStatus("UNPAID");
        record.setDeliveryStatus("PENDING");
        record.setCreatedAt(now);
        return record;
    }

    private PaymentItem payment() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");
        return new PaymentItem(
            "PAY-1",
            "ORD-1",
            9L,
            "mock",
            new BigDecimal("25.00"),
            "SUCCESS",
            "trade-1",
            now,
            now
        );
    }

    private PaymentRecordEntity paymentRecord(String paymentNo, String channel) {
        PaymentRecordEntity payment = new PaymentRecordEntity();
        payment.setPaymentNo(paymentNo);
        payment.setChannel(channel);
        return payment;
    }

    private CardRecordEntity card(Long id) {
        CardRecordEntity card = new CardRecordEntity();
        card.setId(id);
        return card;
    }

    private CardRecordEntity card(Long id, CardCipherService.EncryptedCard encrypted) {
        CardRecordEntity card = card(id);
        card.setCardCiphertext(encrypted.ciphertext());
        card.setCardNonce(encrypted.nonce());
        return card;
    }
}
