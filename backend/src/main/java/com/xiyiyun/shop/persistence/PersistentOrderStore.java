package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.mvp.PaymentCallbackLogItem;
import com.xiyiyun.shop.mvp.PaymentItem;
import com.xiyiyun.shop.mvp.RefundItem;
import com.xiyiyun.shop.mvp.CardSecret;
import com.xiyiyun.shop.persistence.entity.CardRecordEntity;
import com.xiyiyun.shop.persistence.entity.OrderRecordEntity;
import com.xiyiyun.shop.persistence.entity.PaymentCallbackLogEntity;
import com.xiyiyun.shop.persistence.entity.PaymentRecordEntity;
import com.xiyiyun.shop.persistence.entity.RefundRecordEntity;
import com.xiyiyun.shop.persistence.mapper.CardRecordMapper;
import com.xiyiyun.shop.persistence.mapper.OrderRecordMapper;
import com.xiyiyun.shop.persistence.mapper.PaymentCallbackLogMapper;
import com.xiyiyun.shop.persistence.mapper.PaymentRecordMapper;
import com.xiyiyun.shop.persistence.mapper.RefundRecordMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersistentOrderStore {
    private final OrderRecordMapper orderRecordMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final PaymentCallbackLogMapper paymentCallbackLogMapper;
    private final RefundRecordMapper refundRecordMapper;
    private final CardRecordMapper cardRecordMapper;
    private final CardCipherService cardCipherService;
    private final OrderPersistenceMapper persistenceMapper = new OrderPersistenceMapper();

    public PersistentOrderStore(
        OrderRecordMapper orderRecordMapper,
        PaymentRecordMapper paymentRecordMapper,
        PaymentCallbackLogMapper paymentCallbackLogMapper,
        RefundRecordMapper refundRecordMapper,
        CardRecordMapper cardRecordMapper,
        CardCipherService cardCipherService
    ) {
        this.orderRecordMapper = orderRecordMapper;
        this.paymentRecordMapper = paymentRecordMapper;
        this.paymentCallbackLogMapper = paymentCallbackLogMapper;
        this.refundRecordMapper = refundRecordMapper;
        this.cardRecordMapper = cardRecordMapper;
        this.cardCipherService = cardCipherService;
    }

    @Transactional
    public OrderRecordEntity saveOrderSnapshot(OrderItem order) {
        OrderRecordEntity entity = persistenceMapper.toOrderRecord(order);
        orderRecordMapper.upsertByOrderNo(entity);
        entity.setId(orderRecordMapper.findIdByOrderNo(entity.getOrderNo()));
        return entity;
    }

    @Transactional
    public PaymentRecordEntity savePaymentSnapshot(PaymentItem payment, Long orderId) {
        Long resolvedOrderId = orderId == null ? orderRecordMapper.findIdByOrderNo(payment.orderNo()) : orderId;
        PaymentRecordEntity entity = persistenceMapper.toPaymentRecord(payment, resolvedOrderId);
        paymentRecordMapper.upsertByPaymentNo(entity);
        return entity;
    }

    @Transactional
    public PaymentCallbackLogEntity savePaymentCallbackLog(PaymentCallbackLogItem log) {
        PaymentCallbackLogEntity entity = persistenceMapper.toPaymentCallbackLog(log);
        paymentCallbackLogMapper.insert(entity);
        return entity;
    }

    @Transactional
    public RefundRecordEntity saveRefundSnapshot(RefundItem refund) {
        Long orderId = orderRecordMapper.findIdByOrderNo(refund.orderNo());
        Long paymentId = null;
        PaymentRecordEntity payment = refund.paymentNo() == null ? null : paymentRecordMapper.findLatestByOrderNo(refund.orderNo());
        if (payment != null && refund.paymentNo().equals(payment.getPaymentNo())) {
            paymentId = payment.getId();
        }
        RefundRecordEntity entity = persistenceMapper.toRefundRecord(refund, orderId, paymentId);
        refundRecordMapper.upsertByRefundNo(entity);
        return entity;
    }

    @Transactional(readOnly = true)
    public List<PaymentItem> listPayments() {
        return paymentRecordMapper.selectSnapshots().stream()
            .map(persistenceMapper::toPaymentItem)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentCallbackLogItem> listPaymentCallbackLogs() {
        return paymentCallbackLogMapper.selectSnapshots().stream()
            .map(persistenceMapper::toPaymentCallbackLogItem)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<RefundItem> listRefunds() {
        return refundRecordMapper.selectSnapshots().stream()
            .map(persistenceMapper::toRefundItem)
            .toList();
    }

    @Transactional
    public List<CardRecordEntity> lockAvailableCards(Long goodsId, int quantity) {
        return cardRecordMapper.lockAvailableCardsByGoodsId(goodsId, quantity);
    }

    @Transactional
    public int markCardsSold(List<CardRecordEntity> cards, Long orderId, OffsetDateTime soldAt) {
        int updated = 0;
        for (CardRecordEntity card : cards) {
            updated += cardRecordMapper.markSold(card.getId(), orderId, soldAt);
        }
        return updated;
    }

    @Transactional(readOnly = true)
    public List<OrderItem> listOrders() {
        return orderRecordMapper.selectActiveSnapshots().stream()
            .map(record -> persistenceMapper.toOrderItem(record, paymentRecordMapper.findLatestByOrderNo(record.getOrderNo())))
            .toList();
    }

    @Transactional(readOnly = true)
    public Optional<OrderItem> findOrder(String orderNo) {
        return Optional.ofNullable(orderRecordMapper.findByOrderNo(orderNo))
            .map(record -> persistenceMapper.toOrderItem(record, paymentRecordMapper.findLatestByOrderNo(record.getOrderNo())));
    }

    @Transactional
    public CardRecordEntity saveImportedCard(CardSecret card) {
        if (card.goodsId() == null && card.cardKindId() == null) {
            throw new IllegalArgumentException("card must be bound to goods or card kind");
        }
        CardCipherService.EncryptedCard encrypted = cardCipherService.encrypt(card.content());
        CardRecordEntity entity = new CardRecordEntity();
        entity.setId(card.id());
        entity.setGoodsId(card.goodsId());
        entity.setCardKindId(card.cardKindId());
        entity.setBatchNo("MVP");
        entity.setCardCiphertext(encrypted.ciphertext());
        entity.setCardNonce(encrypted.nonce());
        entity.setCardKeyVersion(encrypted.keyVersion());
        entity.setCardHash(encrypted.hash());
        entity.setCardPreview(card.preview());
        entity.setStatus("AVAILABLE".equals(card.status()) ? "UNSOLD" : card.status());
        entity.setCreatedAt(card.importedAt());
        cardRecordMapper.upsertImportedCard(entity);
        return entity;
    }

    @Transactional
    public List<String> deliverCardsForOrder(OrderItem order, Long cardKindId) {
        Long orderId = orderRecordMapper.findIdByOrderNo(order.orderNo());
        if (orderId == null) {
            throw new IllegalStateException("order snapshot not found");
        }
        List<CardRecordEntity> locked = cardKindId == null
            ? cardRecordMapper.lockAvailableCardsByGoodsId(order.goodsId(), order.quantity())
            : cardRecordMapper.lockAvailableCardsByCardKindId(cardKindId, order.quantity());
        if (locked.size() < order.quantity()) {
            throw new IllegalStateException("persistent card stock is insufficient");
        }
        OffsetDateTime soldAt = OffsetDateTime.now();
        List<String> contents = new ArrayList<>();
        for (CardRecordEntity card : locked) {
            int updated = cardRecordMapper.markSold(card.getId(), orderId, soldAt);
            if (updated != 1) {
                throw new IllegalStateException("persistent card stock changed during delivery");
            }
            contents.add(cardCipherService.decrypt(card.getCardCiphertext(), card.getCardNonce()));
        }
        return List.copyOf(contents);
    }
}
