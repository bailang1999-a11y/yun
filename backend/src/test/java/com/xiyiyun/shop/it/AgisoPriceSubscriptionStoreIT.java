package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.persistence.AgisoPriceSubscriptionStore;
import com.xiyiyun.shop.persistence.entity.AgisoPriceSubscriptionEntity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AgisoPriceSubscriptionStoreIT extends AbstractIntegrationTest {
    @Autowired
    private AgisoPriceSubscriptionStore store;

    @Test
    void subscriptionClaimCancelAndExpiredLeaseRecoveryArePersistent() {
        jdbcTemplate.update("DELETE FROM agiso_price_subscriptions");
        OffsetDateTime now = OffsetDateTime.now().withNano(0);

        AgisoPriceSubscriptionEntity first = store.subscribe(
            90001L, "90007", "guid-1", 10004L, new BigDecimal("1.2300"), 10L, now
        );
        assertThat(first.getState()).isEqualTo("ACTIVE");
        assertThat(store.findDue(now, 20))
            .extracting(AgisoPriceSubscriptionEntity::getId)
            .contains(first.getId());
        assertThat(store.claim(first.getId(), now, now.plusSeconds(10), "lease-old")).isTrue();
        assertThat(store.claim(first.getId(), now, now.plusSeconds(10), "lease-duplicate")).isFalse();

        assertThat(store.findDue(now.plusSeconds(9), 20))
            .extracting(AgisoPriceSubscriptionEntity::getId)
            .doesNotContain(first.getId());
        assertThat(store.findDue(now.plusSeconds(10), 20))
            .extracting(AgisoPriceSubscriptionEntity::getId)
            .contains(first.getId());
        assertThat(store.claim(first.getId(), now.plusSeconds(10), now.plusSeconds(20), "lease-new")).isTrue();

        assertThat(store.markUnchanged(first.getId(), "lease-old", now.plusSeconds(30))).isFalse();
        assertThat(store.markUnchanged(first.getId(), "lease-new", now.plusSeconds(30))).isTrue();
        assertThat(store.claim(first.getId(), now.plusSeconds(30), now.plusSeconds(40), "lease-cancel")).isTrue();

        assertThat(store.cancel(90001L, "guid-1", 10004L)).isTrue();
        assertThat(store.markNotified(
            first.getId(), "lease-cancel", new BigDecimal("2.0000"), 11L, now.plusSeconds(31), now.plusSeconds(50)
        )).isFalse();
        assertThat(store.findDue(now.plusMinutes(1), 20))
            .extracting(AgisoPriceSubscriptionEntity::getId)
            .doesNotContain(first.getId());

        AgisoPriceSubscriptionEntity reactivated = store.subscribe(
            90001L, "90007", "guid-1", 10004L, new BigDecimal("3.4500"), 9L, now.plusSeconds(30)
        );
        assertThat(reactivated.getId()).isEqualTo(first.getId());
        assertThat(reactivated.getState()).isEqualTo("ACTIVE");
        assertThat(reactivated.getLastNotifiedPrice()).isEqualByComparingTo("3.4500");
        assertThat(reactivated.getLastPriceVer()).isEqualTo(10L);
    }
}
