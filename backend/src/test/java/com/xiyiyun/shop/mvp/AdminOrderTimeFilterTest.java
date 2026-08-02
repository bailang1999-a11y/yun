package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminOrderTimeFilterTest {
    @Test
    void validCreatedFromIsPassedToRepository() {
        InMemoryShopRepository repository = mock(InMemoryShopRepository.class);
        AdminMvpController controller = controller(repository);
        OffsetDateTime createdFrom = OffsetDateTime.parse("2026-08-03T00:00:00+08:00");
        when(repository.pageOrders(null, null, null, createdFrom, null, 10, 0))
            .thenReturn(new PageSlice<>(List.of(), 0));

        controller.orders(null, null, null, createdFrom.toString(), 1, 10);

        verify(repository).pageOrders(
            eq(null), eq(null), eq(null), eq(createdFrom), eq(null), eq(10), eq(0L)
        );
    }

    @Test
    void invalidCreatedFromIsRejectedClearly() {
        AdminMvpController controller = controller(mock(InMemoryShopRepository.class));

        assertThatThrownBy(() -> controller.orders(null, null, null, "today", 1, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("订单创建时间格式不正确");
    }

    private AdminMvpController controller(InMemoryShopRepository repository) {
        return new AdminMvpController(repository, mock(WeComRobotNotificationService.class), "uploads");
    }
}
