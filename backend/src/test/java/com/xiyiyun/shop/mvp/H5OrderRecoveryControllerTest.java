package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.ApiResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class H5OrderRecoveryControllerTest {

    @Test
    void orderByRequestUsesTheAuthenticatedUserScope() {
        InMemoryShopRepository repository = mock(InMemoryShopRepository.class);
        UserItem user = mock(UserItem.class);
        OrderItem order = mock(OrderItem.class);
        when(user.id()).thenReturn(90001L);
        when(repository.findUserByToken("Bearer token")).thenReturn(Optional.of(user));
        when(repository.findOrderByRequestId(90001L, "request-1")).thenReturn(Optional.of(order));
        H5MvpController controller = new H5MvpController(repository, mock(AlipayPaymentFacade.class));

        ApiResponse<OrderItem> response = controller.orderByRequest("Bearer token", "request-1");

        assertThat(response.code()).isZero();
        assertThat(response.data()).isSameAs(order);
        verify(repository).findOrderByRequestId(90001L, "request-1");
    }

    @Test
    void orderByRequestRejectsMissingLogin() {
        InMemoryShopRepository repository = mock(InMemoryShopRepository.class);
        when(repository.findUserByToken(null)).thenReturn(Optional.empty());
        H5MvpController controller = new H5MvpController(repository, mock(AlipayPaymentFacade.class));

        ApiResponse<OrderItem> response = controller.orderByRequest(null, "request-1");

        assertThat(response.code()).isEqualTo(-1);
        assertThat(response.message()).isEqualTo("unauthorized");
    }
}
