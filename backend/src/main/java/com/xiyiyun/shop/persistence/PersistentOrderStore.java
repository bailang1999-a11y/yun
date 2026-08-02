package com.xiyiyun.shop.persistence;

import com.xiyiyun.shop.GoodsType;
import com.xiyiyun.shop.mvp.OrderItem;
import com.xiyiyun.shop.mvp.PageSlice;
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
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersistentOrderStore {
    private static final Logger log = LoggerFactory.getLogger(PersistentOrderStore.class);

    private final OrderRecordMapper orderRecordMapper;
    private final PaymentRecordMapper paymentRecordMapper;
    private final PaymentCallbackLogMapper paymentCallbackLogMapper;
    private final RefundRecordMapper refundRecordMapper;
    private final CardRecordMapper cardRecordMapper;
    private final CardCipherService cardCipherService;
    private final MemberOrderCallbackTaskStore memberCallbackTaskStore;
    private final WeComRobotDeliveryTaskStore weComRobotDeliveryTaskStore;
    private final OrderPersistenceMapper persistenceMapper = new OrderPersistenceMapper();

    public PersistentOrderStore(
        OrderRecordMapper orderRecordMapper,
        PaymentRecordMapper paymentRecordMapper,
        PaymentCallbackLogMapper paymentCallbackLogMapper,
        RefundRecordMapper refundRecordMapper,
        CardRecordMapper cardRecordMapper,
        CardCipherService cardCipherService
    ) {
        this(
            orderRecordMapper,
            paymentRecordMapper,
            paymentCallbackLogMapper,
            refundRecordMapper,
            cardRecordMapper,
            cardCipherService,
            null,
            null
        );
    }

    public PersistentOrderStore(
        OrderRecordMapper orderRecordMapper,
        PaymentRecordMapper paymentRecordMapper,
        PaymentCallbackLogMapper paymentCallbackLogMapper,
        RefundRecordMapper refundRecordMapper,
        CardRecordMapper cardRecordMapper,
        CardCipherService cardCipherService,
        MemberOrderCallbackTaskStore memberCallbackTaskStore
    ) {
        this(
            orderRecordMapper, paymentRecordMapper, paymentCallbackLogMapper, refundRecordMapper,
            cardRecordMapper, cardCipherService, memberCallbackTaskStore, null
        );
    }

    @Autowired
    public PersistentOrderStore(
        OrderRecordMapper orderRecordMapper,
        PaymentRecordMapper paymentRecordMapper,
        PaymentCallbackLogMapper paymentCallbackLogMapper,
        RefundRecordMapper refundRecordMapper,
        CardRecordMapper cardRecordMapper,
        CardCipherService cardCipherService,
        MemberOrderCallbackTaskStore memberCallbackTaskStore,
        WeComRobotDeliveryTaskStore weComRobotDeliveryTaskStore
    ) {
        this.orderRecordMapper = orderRecordMapper;
        this.paymentRecordMapper = paymentRecordMapper;
        this.paymentCallbackLogMapper = paymentCallbackLogMapper;
        this.refundRecordMapper = refundRecordMapper;
        this.cardRecordMapper = cardRecordMapper;
        this.cardCipherService = cardCipherService;
        this.memberCallbackTaskStore = memberCallbackTaskStore;
        this.weComRobotDeliveryTaskStore = weComRobotDeliveryTaskStore;
    }

    @Transactional
    public OrderRecordEntity saveOrderSnapshot(OrderItem order) {
        return saveOrderSnapshot(order, null);
    }

    @Transactional
    public OrderRecordEntity saveOrderSnapshot(OrderItem order, BigDecimal externalMaxAmount) {
        OrderRecordEntity entity = persistenceMapper.toOrderRecord(order, externalMaxAmount);
        orderRecordMapper.upsertByOrderNo(entity);
        bindUpstreamOrderNoIfPresent(entity);
        entity.setId(orderRecordMapper.findIdByOrderNo(entity.getOrderNo()));
        if (order.goodsType() == GoodsType.CARD && entity.getId() != null) {
            String cardIdsJson = persistenceMapper.toCardIdsJson(
                cardRecordMapper.selectSoldByOrderId(entity.getId()).stream().map(CardRecordEntity::getId).toList()
            );
            orderRecordMapper.updateDeliveryCardIds(entity.getOrderNo(), cardIdsJson);
            entity.setDeliveryCardIdsJson(cardIdsJson);
        }
        if (memberCallbackTaskStore != null) {
            memberCallbackTaskStore.registerTerminalOrder(order);
        }
        if (weComRobotDeliveryTaskStore != null) {
            weComRobotDeliveryTaskStore.registerOrderEvents(order);
        }
        return entity;
    }

    @Transactional
    public boolean saveExternalMaxAmount(String orderNo, Long userId, BigDecimal externalMaxAmount) {
        return orderRecordMapper.saveExternalMaxAmount(orderNo, userId, externalMaxAmount) == 1;
    }

    /**
     * 落上游订单号（缺陷 A4）。
     *
     * <p>走独立的按 order_no 定位的 write-once UPDATE，理由见
     * {@link OrderRecordMapper#bindUpstreamOrderNo}：混进 upsert 的 ODKU 会在撞
     * uk_orders_upstream 时改写<b>另一笔</b>订单。
     *
     * <p>撞唯一索引说明"这个上游单号已经属于别的订单"，即重复采购的强信号。
     * 此处<b>不让整个快照保存失败</b>（订单状态流转已由 CAS 决定，回滚只会让状态与事实更不一致），
     * 而是记 ERROR 日志并把两个订单号都打出来，交由对账任务与人工处理。
     */
    private void bindUpstreamOrderNoIfPresent(OrderRecordEntity entity) {
        String upstreamOrderNo = entity.getUpstreamOrderNo();
        if (upstreamOrderNo == null || upstreamOrderNo.isBlank()) {
            return;
        }
        String existing = orderRecordMapper.selectUpstreamOrderNo(entity.getOrderNo());
        if (upstreamOrderNo.equals(existing)) {
            return;
        }
        if (existing != null && !existing.isBlank()) {
            // write-once：已落库的采购凭据不允许被后续快照覆盖
            log.warn("order {} already bound upstream order no (kept), incoming value ignored",
                entity.getOrderNo());
            entity.setUpstreamOrderNo(existing);
            return;
        }
        try {
            orderRecordMapper.bindUpstreamOrderNo(entity.getOrderNo(), upstreamOrderNo);
        } catch (DuplicateKeyException ex) {
            log.error("upstream order no conflict: order {} tried to bind an upstream no already owned "
                + "by another order — possible duplicate procurement, needs manual reconciliation",
                entity.getOrderNo(), ex);
            entity.setUpstreamOrderNo(null);
        }
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

    /**
     * 批次8C：支付流水分页，{@code LIMIT/OFFSET} 与 {@code COUNT(*)} 都在 SQL 里。
     *
     * <p>count 与取数是两条独立 SQL，因此必须共用同一个 WHERE、并跑在同一个只读事务里：
     * InnoDB 可重复读让两条语句看到同一个一致性快照，
     * 否则并发写入会让 total 与本页数据来自不同时刻，出现「总数 41 但翻到第 5 页是空的」。
     */
    @Transactional(readOnly = true)
    public PageSlice<PaymentItem> pagePayments(int limit, long offset) {
        long total = paymentRecordMapper.countSnapshots();
        if (total <= offset) {
            // 越界页不必再查数据，但 total 仍要如实返回，前端才能把页码收回到有效范围。
            return new PageSlice<>(List.of(), total);
        }
        List<PaymentItem> items = paymentRecordMapper.selectSnapshotPage(limit, offset).stream()
            .map(persistenceMapper::toPaymentItem)
            .toList();
        return new PageSlice<>(items, total);
    }

    @Transactional(readOnly = true)
    public List<PaymentCallbackLogItem> listPaymentCallbackLogs() {
        return paymentCallbackLogMapper.selectSnapshots().stream()
            .map(persistenceMapper::toPaymentCallbackLogItem)
            .toList();
    }

    /** 批次8C：支付回调日志分页。count 与取数同 WHERE、同只读事务，理由见 {@link #pagePayments}。 */
    @Transactional(readOnly = true)
    public PageSlice<PaymentCallbackLogItem> pagePaymentCallbackLogs(int limit, long offset) {
        long total = paymentCallbackLogMapper.countSnapshots();
        if (total <= offset) {
            return new PageSlice<>(List.of(), total);
        }
        List<PaymentCallbackLogItem> items = paymentCallbackLogMapper.selectSnapshotPage(limit, offset).stream()
            .map(persistenceMapper::toPaymentCallbackLogItem)
            .toList();
        return new PageSlice<>(items, total);
    }

    @Transactional(readOnly = true)
    public List<RefundItem> listRefunds() {
        return refundRecordMapper.selectSnapshots().stream()
            .map(persistenceMapper::toRefundItem)
            .toList();
    }

    /**
     * 批次8C：退款流水分页。
     *
     * <p>取数带 orders/payment_records 的 LEFT JOIN、count 不带，这不是遗漏：
     * 见 {@code RefundRecordMapper.countSnapshots} 的说明 —— 主键等值的 LEFT JOIN 不改变左表行数。
     * 两条 SQL 的<b>筛选条件</b>仍然一致，且同处一个只读事务，total 与本页数据来自同一快照。
     */
    @Transactional(readOnly = true)
    public PageSlice<RefundItem> pageRefunds(int limit, long offset) {
        long total = refundRecordMapper.countSnapshots();
        if (total <= offset) {
            return new PageSlice<>(List.of(), total);
        }
        List<RefundItem> items = refundRecordMapper.selectSnapshotPage(limit, offset).stream()
            .map(persistenceMapper::toRefundItem)
            .toList();
        return new PageSlice<>(items, total);
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
            .map(this::toOrderItem)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderItem> unsettledOrderCandidates(
        OffsetDateTime deadline,
        int limit,
        boolean withoutCallback
    ) {
        if (deadline == null || limit <= 0) {
            return List.of();
        }
        return orderRecordMapper.selectUnsettledCandidates(deadline, limit, withoutCallback).stream()
            .map(this::toOrderItem)
            .toList();
    }

    /**
     * 批次8C：带筛选的分页订单查询，{@code LIMIT/OFFSET} 与 {@code COUNT(*)} 都在 SQL 里。
     *
     * <p>两条 SQL 共用同一个 {@code WHERE}，并且在同一个只读事务里执行，
     * 因此 {@code total} 与本页数据看到的是同一个一致性快照
     * （InnoDB 可重复读），不会出现「总数 41 但翻到第 5 页是空的」。
     *
     * @param search 关键字，null/空白表示不筛选；内部做 LIKE 元字符转义与小写归一
     * @param status 订单状态，比较时归一为小写，与内存实现的 {@code normalize} 一致
     * @param userId 限定用户，null 表示不限（管理端）
     * @param limit 本页条数，调用方已归一到 1..N
     * @param offset 跳过行数
     */
    @Transactional(readOnly = true)
    public PageSlice<OrderItem> pageOrders(
        String search,
        String status,
        String goodsType,
        Long userId,
        int limit,
        long offset
    ) {
        return pageOrders(search, status, goodsType, null, userId, limit, offset);
    }

    @Transactional(readOnly = true)
    public PageSlice<OrderItem> pageOrders(
        String search,
        String status,
        String goodsType,
        OffsetDateTime createdFrom,
        Long userId,
        int limit,
        long offset
    ) {
        String keyword = likeKeyword(search);
        String normalizedStatus = filterValue(status);
        String normalizedGoodsType = filterValue(goodsType);
        long total = orderRecordMapper.countSnapshots(
            keyword, normalizedStatus, normalizedGoodsType, createdFrom, userId
        );
        if (total <= offset) {
            // 越界页不必再查数据，但 total 仍要如实返回，前端才能把页码收回到有效范围。
            return new PageSlice<>(List.of(), total);
        }
        List<OrderItem> items = orderRecordMapper
            .selectSnapshotPage(
                keyword, normalizedStatus, normalizedGoodsType, createdFrom, userId, limit, offset
            )
            .stream()
            .map(this::toOrderItem)
            .toList();
        return new PageSlice<>(items, total);
    }

    @Transactional(readOnly = true)
    public Optional<OrderItem> findOrderByRequestId(Long userId, String requestId) {
        if (userId == null || requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(orderRecordMapper.findByUserAndRequestId(userId, requestId))
            .map(this::toOrderItem);
    }

    /** 空白筛选值统一成 null，让 SQL 里的 {@code #{x} IS NULL} 分支短路掉该条件。 */
    private String filterValue(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 关键字转成可安全拼进 LIKE 的模式串。
     *
     * <p>用户输入里的 {@code %}、{@code _} 和转义符 {@code \} 本身都是 LIKE 元字符，
     * 不转义的话搜 "50%" 会退化成前缀通配、搜 "a_b" 会匹配 "axb"。
     * 转义符必须先处理，否则会把后面补上的反斜杠再转义一遍。
     */
    private String likeKeyword(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        String escaped = normalized
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    @Transactional(readOnly = true)
    public Optional<OrderItem> findOrder(String orderNo) {
        return Optional.ofNullable(orderRecordMapper.findByOrderNo(orderNo))
            .map(this::toOrderItem);
    }

    @Transactional
    public boolean deleteOrderData(String orderNo) {
        Long orderId = orderRecordMapper.findIdByOrderNo(orderNo);
        if (memberCallbackTaskStore != null) {
            memberCallbackTaskStore.deleteByOrderNo(orderNo);
        }
        if (weComRobotDeliveryTaskStore != null) {
            weComRobotDeliveryTaskStore.deleteByOrderNo(orderNo);
        }
        paymentCallbackLogMapper.hardDeleteByOrderNo(orderNo);
        if (orderId != null) {
            cardRecordMapper.releaseByOrderId(orderId);
            refundRecordMapper.hardDeleteByOrderId(orderId);
        }
        paymentRecordMapper.hardDeleteByOrderNo(orderNo);
        orderRecordMapper.hardDeleteDeliveryTasksByOrderNo(orderNo);
        orderRecordMapper.hardDeleteStatusLogsByOrderNo(orderNo);
        return orderRecordMapper.hardDeleteByOrderNo(orderNo) > 0;
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
    public void deleteCardsByGoods(Long goodsId) {
        if (goodsId != null) {
            cardRecordMapper.hardDeleteByGoods(goodsId);
        }
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

    private OrderItem toOrderItem(OrderRecordEntity record) {
        List<String> deliveryItems = null;
        if (GoodsType.CARD.name().equals(record.getGoodsType()) && record.getId() != null) {
            List<CardRecordEntity> soldCards = cardRecordMapper.selectSoldByOrderId(record.getId());
            if (!soldCards.isEmpty()) {
                deliveryItems = soldCards.stream()
                    .map(card -> cardCipherService.decrypt(card.getCardCiphertext(), card.getCardNonce()))
                    .toList();
            }
        }
        return persistenceMapper.toOrderItem(
            record,
            paymentRecordMapper.findLatestByOrderNo(record.getOrderNo()),
            deliveryItems
        );
    }
}
