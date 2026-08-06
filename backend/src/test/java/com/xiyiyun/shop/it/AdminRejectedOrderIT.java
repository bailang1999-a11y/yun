package com.xiyiyun.shop.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.xiyiyun.shop.ApiResponse;
import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.it.support.ItFixtures;
import com.xiyiyun.shop.mvp.AdminMvpController;
import com.xiyiyun.shop.mvp.OrderSummaryItem;
import com.xiyiyun.shop.mvp.PageResult;
import com.xiyiyun.shop.mvp.RejectedOrderItem;
import com.xiyiyun.shop.mvp.WeComRobotNotificationService;
import com.xiyiyun.shop.persistence.AgisoRejectedOrderStore;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

class AdminRejectedOrderIT extends AbstractIntegrationTest {
    @Autowired
    private AgisoRejectedOrderStore rejectedOrderStore;

    private AdminMvpController controller;

    @BeforeEach
    void setUpController() {
        controller = new AdminMvpController(repository, mock(WeComRobotNotificationService.class), "uploads");
        ReflectionTestUtils.setField(controller, "rejectedOrderStore", rejectedOrderStore);
    }

    @Test
    void adminListFilterDetailAndSummaryIncludeRejectedOrders() {
        fixtures.insertUserGroup();
        fixtures.insertCategory();
        fixtures.insertUser(new BigDecimal("100.00"));
        fixtures.insertGoods("DIRECT", 10, new BigDecimal("4.00"), null);
        fixtures.insertOrder(
            "IT-NORMAL-ORDER", ItFixtures.USER_ID, ItFixtures.GOODS_ID, "DIRECT", "DELIVERED", 1,
            new BigDecimal("4.00")
        );
        rejectedOrderStore.record(
            ItFixtures.USER_ID,
            "13900000001",
            Map.of(
                "orderNo", "AGISO-REJECTED-ADMIN", "productNo", String.valueOf(ItFixtures.GOODS_ID),
                "buyNum", 1, "maxAmount", "3.99"
            ),
            repository.findGoods(ItFixtures.GOODS_ID).orElseThrow(),
            GoodsType.DIRECT,
            new BigDecimal("4.00"),
            "43040567965",
            Map.of("account", "43040567965"),
            "1220",
            "实际支付价格低于系统要求价格"
        );

        ApiResponse<PageResult<Object>> allResponse = controller.orders(
            null, null, null, null, 1, 10
        );
        assertThat(allResponse.data().total()).isEqualTo(2);
        assertThat(allResponse.data().items()).hasSize(2);

        ApiResponse<PageResult<Object>> rejectedResponse = controller.orders(
            "AGISO-REJECTED-ADMIN", "REJECTED", "DIRECT", null, 1, 10
        );
        assertThat(rejectedResponse.data().total()).isEqualTo(1);
        RejectedOrderItem rejected = (RejectedOrderItem) rejectedResponse.data().items().getFirst();
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.requestId()).isEqualTo("AGISO-REJECTED-ADMIN");
        assertThat(rejected.externalMaxAmount()).isEqualByComparingTo("3.9900");
        assertThat(rejected.expectedAmount()).isEqualByComparingTo("4.0000");

        assertThat(controller.orderDetail(rejected.orderNo()).data()).isEqualTo(rejected);

        OrderSummaryItem rejectedSummary = controller.orderSummary(
            null, "REJECTED", null, null
        ).data();
        assertThat(rejectedSummary.total()).isEqualTo(1);
        assertThat(rejectedSummary.failedCount()).isEqualTo(1);
        assertThat(rejectedSummary.externalAmount()).isEqualByComparingTo("3.9900");

        OrderSummaryItem allSummary = controller.orderSummary(null, null, null, null).data();
        assertThat(allSummary.total()).isEqualTo(2);
        assertThat(allSummary.failedCount()).isEqualTo(1);
    }
}
