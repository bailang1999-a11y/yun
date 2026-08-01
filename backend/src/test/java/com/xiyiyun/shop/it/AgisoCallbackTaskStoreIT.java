package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.persistence.AgisoCallbackTaskStore;
import com.xiyiyun.shop.persistence.entity.AgisoCallbackTaskEntity;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AgisoCallbackTaskStoreIT extends AbstractIntegrationTest {
    @Autowired
    private AgisoCallbackTaskStore taskStore;

    @Test
    void callbackTaskClaimRetrySentAndLeaseRecoveryArePersistent() {
        jdbcTemplate.update("DELETE FROM agiso_callback_tasks");
        OffsetDateTime now = OffsetDateTime.now().withNano(0);

        AgisoCallbackTaskEntity first = taskStore.registerPending(
            90001L, "external-1", "https://mai.91kami.com/callback/first", "CARD", now.plusMinutes(10)
        );
        assertThat(first.getState()).isEqualTo("PENDING");
        assertThat(taskStore.findDue(now.plusSeconds(1), 20))
            .extracting(AgisoCallbackTaskEntity::getId)
            .doesNotContain(first.getId());
        assertThat(taskStore.bindOrder(90001L, "external-1", "local-1", now.plusSeconds(2))).isTrue();
        assertThat(taskStore.findDue(now.plusSeconds(1), 20))
            .extracting(AgisoCallbackTaskEntity::getId)
            .doesNotContain(first.getId());
        assertThat(taskStore.findDue(now.plusSeconds(2), 20))
            .extracting(AgisoCallbackTaskEntity::getId)
            .contains(first.getId());
        assertThat(taskStore.claim(first.getId(), now.plusSeconds(2), now.plusSeconds(12))).isTrue();
        assertThat(taskStore.claim(first.getId(), now.plusSeconds(2), now.plusSeconds(12))).isFalse();
        assertThat(taskStore.retry(first.getId(), now.plusSeconds(1), "temporary failure")).isTrue();
        assertThat(taskStore.claim(first.getId(), now.plusSeconds(1), now.plusSeconds(11))).isTrue();
        assertThat(taskStore.markSent(first.getId(), now.plusSeconds(2))).isTrue();

        AgisoCallbackTaskEntity repeated = taskStore.registerPending(
            90001L, "external-1", "https://mai.91kami.com/callback/changed", "CARD", now.plusSeconds(3)
        );
        assertThat(repeated.getState()).isEqualTo("SENT");
        assertThat(repeated.getCallbackUrl()).isEqualTo("https://mai.91kami.com/callback/first");

        AgisoCallbackTaskEntity leased = taskStore.registerPending(
            90001L, "external-2", "https://mai.91kami.com/callback/leased", "DIRECT", now
        );
        assertThat(taskStore.claim(leased.getId(), now, now.plusSeconds(10))).isTrue();
        assertThat(taskStore.findDue(now.plusSeconds(9), 20))
            .extracting(AgisoCallbackTaskEntity::getId)
            .doesNotContain(leased.getId());
        assertThat(taskStore.findDue(now.plusSeconds(10), 20))
            .extracting(AgisoCallbackTaskEntity::getId)
            .contains(leased.getId());
        assertThat(taskStore.claim(leased.getId(), now.plusSeconds(10), now.plusSeconds(20))).isTrue();
    }
}
