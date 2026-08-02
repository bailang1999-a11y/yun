package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.persistence.entity.OrderRecordEntity;
import com.xiyiyun.shop.persistence.mapper.OrderRecordMapper;
import com.xiyiyun.shop.persistence.mapper.UserBalanceTransactionMapper;
import java.math.BigDecimal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates an order and reserves its stock in one database transaction. */
@Service
public class OrderCreationStore {
    private final OrderRecordMapper orderRecordMapper;
    private final UserBalanceTransactionMapper stockMapper;
    private final WeComRobotDeliveryTaskStore weComRobotDeliveryTaskStore;
    private final OrderPersistenceMapper persistenceMapper = new OrderPersistenceMapper();

    public OrderCreationStore(
        OrderRecordMapper orderRecordMapper,
        UserBalanceTransactionMapper stockMapper,
        WeComRobotDeliveryTaskStore weComRobotDeliveryTaskStore
    ) {
        this.orderRecordMapper = orderRecordMapper;
        this.stockMapper = stockMapper;
        this.weComRobotDeliveryTaskStore = weComRobotDeliveryTaskStore;
    }

    @Transactional
    public CreateResult create(OrderItem order, boolean reserveStock) {
        return create(order, reserveStock, null);
    }

    @Transactional
    public CreateResult create(OrderItem order, boolean reserveStock, BigDecimal externalMaxAmount) {
        OrderRecordEntity entity = persistenceMapper.toOrderRecord(order, externalMaxAmount);
        try {
            orderRecordMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            OrderRecordEntity existing = findByRequestId(order.userId(), order.requestId());
            if (existing != null) {
                saveExternalMaxAmount(existing.getOrderNo(), order.userId(), externalMaxAmount);
                return new CreateResult(persistenceMapper.toOrderItem(existing), false);
            }
            throw ex;
        }

        if (reserveStock && stockMapper.deductGoodsStock(order.goodsId(), order.quantity()) != 1) {
            throw new IllegalStateException("goods stock is insufficient");
        }
        weComRobotDeliveryTaskStore.registerOrderEvents(order);
        return new CreateResult(order, true);
    }

    @Transactional
    public void saveExternalMaxAmount(String orderNo, Long userId, BigDecimal externalMaxAmount) {
        if (externalMaxAmount != null
            && orderRecordMapper.saveExternalMaxAmount(orderNo, userId, externalMaxAmount) != 1) {
            throw new IllegalStateException("order external maxAmount was not saved");
        }
    }

    private OrderRecordEntity findByRequestId(Long userId, String requestId) {
        if (userId == null || requestId == null || requestId.isBlank()) {
            return null;
        }
        return orderRecordMapper.findByUserAndRequestId(userId, requestId.trim());
    }

    public record CreateResult(OrderItem order, boolean created) {
    }
}
