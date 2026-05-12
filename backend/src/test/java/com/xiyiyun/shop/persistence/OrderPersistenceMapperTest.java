package com.xiyiyun.shop.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.mvp.PaymentCallbackLogItem;
import com.xiyiyun.shop.mvp.PaymentItem;
import com.xiyiyun.shop.persistence.entity.OrderRecordEntity;
import com.xiyiyun.shop.persistence.entity.PaymentCallbackLogEntity;
import com.xiyiyun.shop.persistence.entity.PaymentRecordEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderPersistenceMapperTest {
    private final OrderPersistenceMapper mapper = new OrderPersistenceMapper();

    @Test
    void mapsOrderSnapshotToDatabaseEntity() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");
        OrderItem order = new OrderItem(
            "ORD-1",
            9L,
            "buyer",
            11L,
            "Tencent Card",
            GoodsType.CARD,
            "H5",
            2,
            new BigDecimal("12.50"),
            new BigDecimal("25.00"),
            OrderStatus.DELIVERING,
            "acct",
            "remark",
            "req-1",
            "PAY-1",
            "mock",
            List.of("A", "B"),
            List.of(),
            "processing",
            now,
            now.plusMinutes(1),
            null
        );

        OrderRecordEntity entity = mapper.toOrderRecord(order);

        assertThat(entity.getOrderNo()).isEqualTo("ORD-1");
        assertThat(entity.getUserId()).isEqualTo(9L);
        assertThat(entity.getSourcePlatformCode()).isEqualTo("H5");
        assertThat(entity.getGoodsType()).isEqualTo("CARD");
        assertThat(entity.getTotalAmount()).isEqualByComparingTo("25.00");
        assertThat(entity.getDeliveryStatus()).isEqualTo("PROCESSING");
        assertThat(entity.getPaidAt()).isEqualTo(now.plusMinutes(1));
        assertThat(entity.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void mapsOrderRecordBackToOrderItem() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");
        OrderRecordEntity record = new OrderRecordEntity();
        record.setOrderNo("ORD-1");
        record.setUserId(9L);
        record.setSourcePlatformCode("H5");
        record.setGoodsId(11L);
        record.setGoodsName("Tencent Card");
        record.setGoodsType("CARD");
        record.setQuantity(2);
        record.setUnitPrice(new BigDecimal("12.50"));
        record.setPayAmount(new BigDecimal("25.00"));
        record.setStatus("DELIVERED");
        record.setDeliveryStatus("DELIVERED");
        record.setRechargeAccount("acct");
        record.setBuyerRemark("remark");
        record.setRequestId("req-1");
        record.setCreatedAt(now);
        record.setPaidAt(now.plusMinutes(1));
        record.setDeliveredAt(now.plusMinutes(2));

        OrderItem item = mapper.toOrderItem(record);

        assertThat(item.orderNo()).isEqualTo("ORD-1");
        assertThat(item.goodsType()).isEqualTo(GoodsType.CARD);
        assertThat(item.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(item.payAmount()).isEqualByComparingTo("25.00");
        assertThat(item.createdAt()).isEqualTo(now);
    }

    @Test
    void mapsPaymentRecordIntoOrderItemPaymentFields() {
        OrderRecordEntity record = new OrderRecordEntity();
        record.setOrderNo("ORD-1");
        record.setUserId(9L);
        record.setGoodsId(11L);
        record.setGoodsName("Tencent Card");
        record.setGoodsType("CARD");
        record.setQuantity(1);
        record.setUnitPrice(new BigDecimal("25.00"));
        record.setPayAmount(new BigDecimal("25.00"));
        record.setStatus("PAID");
        record.setDeliveryStatus("PENDING");
        record.setCreatedAt(OffsetDateTime.parse("2026-05-02T10:00:00+08:00"));
        PaymentRecordEntity payment = new PaymentRecordEntity();
        payment.setPaymentNo("PAY-1");
        payment.setChannel("balance");

        OrderItem item = mapper.toOrderItem(record, payment);

        assertThat(item.paymentNo()).isEqualTo("PAY-1");
        assertThat(item.payMethod()).isEqualTo("balance");
    }

    @Test
    void mapsPaymentSnapshotWithPaymentNumberFallbackAsOutTradeNo() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");
        PaymentItem payment = new PaymentItem(
            "PAY-1",
            "ORD-1",
            9L,
            "mock",
            new BigDecimal("25.00"),
            "SUCCESS",
            null,
            now,
            now.plusMinutes(2)
        );

        PaymentRecordEntity entity = mapper.toPaymentRecord(payment, 101L);

        assertThat(entity.getOrderId()).isEqualTo(101L);
        assertThat(entity.getPaymentNo()).isEqualTo("PAY-1");
        assertThat(entity.getOutTradeNo()).isEqualTo("PAY-1");
        assertThat(entity.getAmount()).isEqualByComparingTo("25.00");
        assertThat(entity.getPaidAt()).isEqualTo(now.plusMinutes(2));
    }

    @Test
    void mapsPaymentCallbackLogSnapshot() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-02T10:00:00+08:00");
        PaymentCallbackLogItem log = new PaymentCallbackLogItem(
            7L,
            "mock",
            "PAY-1",
            "ORD-1",
            "SUCCESS",
            "trade-1",
            "ACCEPTED",
            "ok",
            now
        );

        PaymentCallbackLogEntity entity = mapper.toPaymentCallbackLog(log);

        assertThat(entity.getId()).isEqualTo(7L);
        assertThat(entity.getCallbackStatus()).isEqualTo("SUCCESS");
        assertThat(entity.getChannelTradeNo()).isEqualTo("trade-1");
        assertThat(entity.getResult()).isEqualTo("ACCEPTED");
        assertThat(entity.getCreatedAt()).isEqualTo(now);
    }
}
