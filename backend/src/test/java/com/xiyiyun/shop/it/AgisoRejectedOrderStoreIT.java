package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.it.support.ItFixtures;
import com.xiyiyun.shop.mvp.GoodsItem;
import com.xiyiyun.shop.mvp.PageSlice;
import com.xiyiyun.shop.mvp.RejectedOrderItem;
import com.xiyiyun.shop.persistence.AgisoRejectedOrderStore;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AgisoRejectedOrderStoreIT extends AbstractIntegrationTest {
    @Autowired
    private AgisoRejectedOrderStore store;

    @Test
    void authenticatedBusinessRejectionIsIdempotentAndSearchable() {
        fixtures.insertUserGroup();
        fixtures.insertCategory();
        fixtures.insertUser(new BigDecimal("100.00"));
        fixtures.insertGoods("DIRECT", 10, new BigDecimal("4.00"), null);
        GoodsItem goods = repository.findGoods(ItFixtures.GOODS_ID).orElseThrow();
        Map<String, Object> payload = Map.of(
            "orderNo", "6955026137079485482-00",
            "productNo", String.valueOf(ItFixtures.GOODS_ID),
            "buyNum", 1,
            "maxAmount", "3.99",
            "callbackUrl", "https://example.test/callback"
        );

        store.record(
            ItFixtures.USER_ID, "13900000001", payload, goods, GoodsType.DIRECT, new BigDecimal("4.00"),
            "43040567965", Map.of("account", "43040567965"), "1220", "实际支付价格低于系统要求价格"
        );
        store.record(
            ItFixtures.USER_ID, "13900000001", payload, goods, GoodsType.DIRECT, new BigDecimal("4.00"),
            "43040567965", Map.of("account", "43040567965"), "1220", "实际支付价格低于系统要求价格"
        );

        PageSlice<RejectedOrderItem> page = store.page("6955026137079485482-00", "DIRECT", null, 10, 0);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("REJECTED");
            assertThat(item.requestId()).isEqualTo("6955026137079485482-00");
            assertThat(item.externalMaxAmount()).isEqualByComparingTo("3.9900");
            assertThat(item.expectedAmount()).isEqualByComparingTo("4.0000");
            assertThat(item.rechargeAccount()).isEqualTo("43040567965");
            assertThat(item.rejectionCode()).isEqualTo("1220");
            assertThat(item.attemptCount()).isEqualTo(2);
            assertThat(store.find(item.orderNo())).contains(item);
        });
        assertThat(store.summary(null, null, null).total()).isEqualTo(1);
        assertThat(store.summary(null, null, null).externalAmount()).isEqualByComparingTo("3.9900");
    }

    @Test
    void resolvedRejectionIsHiddenAfterAFormalOrderSucceeds() {
        Map<String, Object> payload = Map.of(
            "orderNo", "AGISO-RESOLVED-1", "productNo", "999999", "buyNum", 1, "maxAmount", "1.00"
        );
        store.record(
            ItFixtures.USER_ID, "member", payload, null, GoodsType.DIRECT, null, "", Map.of(), "1100", "商品不存在或不可售"
        );
        RejectedOrderItem rejected = store.page(null, null, null, 10, 0).items().getFirst();

        store.resolve(ItFixtures.USER_ID, "AGISO-RESOLVED-1", "xiyi-formal-1");

        assertThat(store.page(null, null, null, 10, 0).items()).isEmpty();
        assertThat(store.find(rejected.orderNo())).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT state FROM agiso_rejected_orders WHERE external_order_no = ?", String.class, "AGISO-RESOLVED-1"
        )).isEqualTo("RESOLVED");
    }
}
