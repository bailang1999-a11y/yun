package com.xiyiyun.shop.persistence;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.OrderStatus;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.persistence.entity.OrderRecordEntity;
import com.xiyiyun.shop.persistence.mapper.OrderRecordMapper;
import com.xiyiyun.shop.persistence.mapper.UserBalanceTransactionMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderCreationStoreTest {
    @Test
    void newlyCreatedOrderRegistersTheWeComOutbox() {
        OrderRecordMapper orderMapper = mock(OrderRecordMapper.class);
        UserBalanceTransactionMapper stockMapper = mock(UserBalanceTransactionMapper.class);
        WeComRobotDeliveryTaskStore taskStore = mock(WeComRobotDeliveryTaskStore.class);
        OrderCreationStore store = new OrderCreationStore(orderMapper, stockMapper, taskStore);
        OrderItem order = order();

        when(orderMapper.insert(org.mockito.ArgumentMatchers.any(OrderRecordEntity.class))).thenReturn(1);

        store.create(order, false);

        verify(taskStore).registerOrderEvents(order);
    }

    private OrderItem order() {
        return new OrderItem(
            "ORDER-NEW-1", 90001L, "buyer", 10004L, "测试商品", GoodsType.DIRECT,
            "web", "127.0.0.1", "本地", 1, BigDecimal.ONE, BigDecimal.ONE,
            OrderStatus.UNPAID, "13800138000", Map.of(), "", "REQUEST-1", "", "",
            List.of(), List.of(), "", OffsetDateTime.now(), null, null
        );
    }
}
