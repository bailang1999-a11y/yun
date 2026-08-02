package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.it.support.ItFixtures;
import com.xiyiyun.shop.persistence.PersistentOrderStore;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderExternalMaxAmountIT extends AbstractIntegrationTest {
    @Autowired
    private PersistentOrderStore persistentOrderStore;

    @Test
    void externalMaxAmountIsWriteOnceAndDoesNotChangeThePayAmount() {
        fixtures.seedCardGoodsScenario(new BigDecimal("100.00"), 1, new BigDecimal("2.00"));
        fixtures.insertOrder(
            "IT-EXTERNAL-MAX-1",
            ItFixtures.USER_ID,
            ItFixtures.GOODS_ID,
            "CARD",
            "PAID",
            1,
            new BigDecimal("2.00")
        );

        assertThat(persistentOrderStore.saveExternalMaxAmount(
            "IT-EXTERNAL-MAX-1", ItFixtures.USER_ID, new BigDecimal("9.9900")
        )).isTrue();
        assertThat(persistentOrderStore.saveExternalMaxAmount(
            "IT-EXTERNAL-MAX-1", ItFixtures.USER_ID, new BigDecimal("15.0000")
        )).isTrue();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT external_max_amount FROM orders WHERE order_no = ?",
            BigDecimal.class,
            "IT-EXTERNAL-MAX-1"
        )).isEqualByComparingTo("9.9900");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT pay_amount FROM orders WHERE order_no = ?",
            BigDecimal.class,
            "IT-EXTERNAL-MAX-1"
        )).isEqualByComparingTo("2.0000");
        assertThat(persistentOrderStore.findOrder("IT-EXTERNAL-MAX-1").orElseThrow().externalMaxAmount())
            .as("admin order responses must expose the downstream maxAmount")
            .isEqualByComparingTo("9.9900");
    }

    @Test
    void anotherMemberCannotWriteTheOrderExternalMaxAmount() {
        fixtures.seedCardGoodsScenario(new BigDecimal("100.00"), 1, new BigDecimal("2.00"));
        fixtures.insertOrder(
            "IT-EXTERNAL-MAX-2",
            ItFixtures.USER_ID,
            ItFixtures.GOODS_ID,
            "CARD",
            "PAID",
            1,
            new BigDecimal("2.00")
        );

        assertThat(persistentOrderStore.saveExternalMaxAmount(
            "IT-EXTERNAL-MAX-2", ItFixtures.USER_ID + 1, new BigDecimal("9.9900")
        )).isFalse();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT external_max_amount FROM orders WHERE order_no = ?",
            BigDecimal.class,
            "IT-EXTERNAL-MAX-2"
        )).isNull();
    }
}
