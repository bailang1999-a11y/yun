package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.persistence.entity.OrderRecordEntity;
import com.xiyiyun.shop.persistence.mapper.OrderRecordMapper;
import com.xiyiyun.shop.persistence.mapper.UserBalanceTransactionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates an order and reserves its stock in one database transaction. */
@Service
public class OrderCreationStore {
    private final OrderRecordMapper orderRecordMapper;
    private final UserBalanceTransactionMapper stockMapper;
    private final OrderPersistenceMapper persistenceMapper = new OrderPersistenceMapper();

    public OrderCreationStore(
        OrderRecordMapper orderRecordMapper,
        UserBalanceTransactionMapper stockMapper
    ) {
        this.orderRecordMapper = orderRecordMapper;
        this.stockMapper = stockMapper;
    }

    @Transactional
    public CreateResult create(OrderItem order, boolean reserveStock) {
        OrderRecordEntity entity = persistenceMapper.toOrderRecord(order);
        try {
            orderRecordMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            OrderRecordEntity existing = findByRequestId(order.userId(), order.requestId());
            if (existing != null) {
                return new CreateResult(persistenceMapper.toOrderItem(existing), false);
            }
            throw ex;
        }

        if (reserveStock && stockMapper.deductGoodsStock(order.goodsId(), order.quantity()) != 1) {
            throw new IllegalStateException("goods stock is insufficient");
        }
        return new CreateResult(order, true);
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
