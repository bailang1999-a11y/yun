package com.xiyiyun.shop.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.persistence.entity.AgisoPriceSubscriptionEntity;
import com.xiyiyun.shop.persistence.mapper.AgisoPriceSubscriptionMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class AgisoPriceSubscriptionStoreTest {
    private final AgisoPriceSubscriptionMapper mapper = mock(AgisoPriceSubscriptionMapper.class);
    private final AgisoPriceSubscriptionStore store = new AgisoPriceSubscriptionStore(mapper);

    @Test
    void subscribeNormalizesKeysRefreshesBaselineAndReturnsStoredSubscription() {
        OffsetDateTime nextCheckAt = OffsetDateTime.parse("2026-07-30T15:00:00+08:00");
        AgisoPriceSubscriptionEntity persisted = new AgisoPriceSubscriptionEntity();
        persisted.setId(11L);
        when(mapper.selectBySubscription(9L, "guid-1", 10004L)).thenReturn(persisted);

        AgisoPriceSubscriptionEntity actual = store.subscribe(
            9L, " 90007 ", " guid-1 ", 10004L, new BigDecimal("1.2300"), 7L, nextCheckAt
        );

        assertThat(actual).isSameAs(persisted);
        verify(mapper).subscribe(
            9L, "90007", "guid-1", 10004L, new BigDecimal("1.2300"), 7L, nextCheckAt
        );
    }

    @Test
    void cancelIsIdempotentWhenSubscriptionDoesNotExist() {
        when(mapper.cancel(9L, "guid-1", 10004L)).thenReturn(0);

        assertThat(store.cancel(9L, " guid-1 ", 10004L)).isTrue();
        verify(mapper).cancel(9L, "guid-1", 10004L);
    }

    @Test
    void dueBatchIsBoundedAndClaimRequiresForwardLease() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-30T15:00:00+08:00");
        when(mapper.selectDue(now, 200)).thenReturn(List.of());

        assertThat(store.findDue(now, 500)).isEmpty();
        verify(mapper).selectDue(now, 200);
        assertThatIllegalArgumentException()
            .isThrownBy(() -> store.claim(1L, now, now, "lease-1"))
            .withMessage("leaseUntil must be after now");
    }

    @Test
    void workerTransitionsReportWhetherCheckingStateWasUpdated() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-30T15:00:00+08:00");
        OffsetDateTime nextCheckAt = now.plusMinutes(1);
        when(mapper.claim(1L, now, now.plusSeconds(30), "lease-1")).thenReturn(1);
        when(mapper.markUnchanged(1L, "lease-1", nextCheckAt)).thenReturn(0);
        when(mapper.markNotified(2L, "lease-2", new BigDecimal("2.0000"), 8L, now, nextCheckAt)).thenReturn(1);
        when(mapper.retry(3L, "lease-3", nextCheckAt, "temporary failure")).thenReturn(1);

        assertThat(store.claim(1L, now, now.plusSeconds(30), "lease-1")).isTrue();
        assertThat(store.markUnchanged(1L, "lease-1", nextCheckAt)).isFalse();
        assertThat(store.markNotified(2L, "lease-2", new BigDecimal("2.0000"), 8L, now, nextCheckAt)).isTrue();
        assertThat(store.retry(3L, "lease-3", nextCheckAt, " temporary failure ")).isTrue();
    }

    @Test
    void invalidSubscriptionValuesAreRejectedBeforePersistence() {
        OffsetDateTime nextCheckAt = OffsetDateTime.parse("2026-07-30T15:00:00+08:00");

        assertThatIllegalArgumentException().isThrownBy(() -> store.subscribe(
            9L, "90007", "guid-1", 0L, BigDecimal.ONE, 1L, nextCheckAt
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> store.subscribe(
            9L, "90007", "guid-1", 10004L, new BigDecimal("-0.01"), 1L, nextCheckAt
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> store.subscribe(
            9L, " ", "guid-1", 10004L, BigDecimal.ONE, 1L, nextCheckAt
        ));
    }

    @Test
    void mapperTransitionsGuardAgainstRevivingCancelledSubscriptions() throws NoSuchMethodException {
        assertThat(updateSql("cancel", Long.class, String.class, Long.class))
            .contains("state IN ('ACTIVE', 'CHECKING')");
        assertThat(updateSql("claim", Long.class, OffsetDateTime.class, OffsetDateTime.class, String.class))
            .contains("state = 'ACTIVE'", "state = 'CHECKING'");
        assertThat(updateSql("markUnchanged", Long.class, String.class, OffsetDateTime.class))
            .contains("WHERE id = #{id} AND state = 'CHECKING' AND lease_token = #{leaseToken}");
        assertThat(updateSql(
            "markNotified",
            Long.class,
            String.class,
            BigDecimal.class,
            long.class,
            OffsetDateTime.class,
            OffsetDateTime.class
        )).contains("WHERE id = #{id} AND state = 'CHECKING' AND lease_token = #{leaseToken}");
        assertThat(updateSql("retry", Long.class, String.class, OffsetDateTime.class, String.class))
            .contains("WHERE id = #{id} AND state = 'CHECKING' AND lease_token = #{leaseToken}");
    }

    @Test
    void repeatedSubscribeReactivatesAndRefreshesTheStoredBaseline() throws NoSuchMethodException {
        assertThat(updateSql(
            "subscribe",
            Long.class,
            String.class,
            String.class,
            Long.class,
            BigDecimal.class,
            long.class,
            OffsetDateTime.class
        )).contains(
            "ON DUPLICATE KEY UPDATE",
            "state = 'ACTIVE'",
            "last_notified_price = VALUES(last_notified_price)",
            "last_price_ver = GREATEST(last_price_ver, VALUES(last_price_ver))",
            "lease_until = NULL",
            "lease_token = NULL",
            "attempt_count = 0"
        );
    }

    private String updateSql(String method, Class<?>... parameterTypes) throws NoSuchMethodException {
        Update annotation = AgisoPriceSubscriptionMapper.class
            .getMethod(method, parameterTypes)
            .getAnnotation(Update.class);
        return String.join(" ", Arrays.stream(annotation.value())
            .map(fragment -> fragment.replaceAll("\\s+", " ").trim())
            .toList());
    }
}
