package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.xiyiyun.shop.persistence.AgisoCallbackTaskStore;
import com.xiyiyun.shop.persistence.entity.AgisoCallbackTaskEntity;
import java.time.OffsetDateTime;
import java.util.List;
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
        assertThat(taskStore.markDeadIfUnbound(first.getId(), "duplicate request failed")).isFalse();
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

        AgisoCallbackTaskEntity orphan = taskStore.registerPending(
            90001L, "external-3", "https://mai.91kami.com/callback/orphan", "DIRECT", now
        );
        assertThat(taskStore.markDeadIfUnbound(orphan.getId(), "order was not created")).isTrue();

        AgisoCallbackTaskEntity legacyPending = taskStore.registerPending(
            90001L, "external-4", "https://mai.91kami.com/callback/legacy-pending", "DIRECT", now.plusMinutes(5)
        );
        assertThat(taskStore.bindOrder(90001L, "external-4", "local-4", now.plusMinutes(5))).isTrue();
        jdbcTemplate.update(
            "UPDATE agiso_callback_tasks SET attempt_count = 9, last_error = ? WHERE id = ?",
            "agiso callback rejected: code=9999 message=FailCode的值非法",
            legacyPending.getId()
        );

        AgisoCallbackTaskEntity legacyDead = taskStore.registerPending(
            90001L, "external-5", "https://mai.91kami.com/callback/legacy-dead", "DIRECT", now
        );
        assertThat(taskStore.bindOrder(90001L, "external-5", "local-5", now)).isTrue();
        assertThat(taskStore.markDead(
            legacyDead.getId(), "agiso callback rejected: code=9999 message=FailCode的值非法"
        )).isTrue();

        assertThat(taskStore.recoverInvalidFailCodeTasks(now)).isEqualTo(2);
        assertThat(taskStore.findDue(now, 20))
            .filteredOn(task -> List.of("external-4", "external-5").contains(task.getRequestId()))
            .allSatisfy(task -> {
                assertThat(task.getState()).isEqualTo("PENDING");
                assertThat(task.getAttemptCount()).isZero();
                assertThat(task.getNextAttemptAt()).isEqualTo(now);
            });
    }
}
