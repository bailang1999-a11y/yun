package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.OrderStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 批次8A：{@link OrderCompensationService} 反向依赖仓储的<b>窄接缝</b>。
 *
 * <p>补偿任务只需要这几项能力：读订单快照、订单状态 CAS、归还预扣库存、退款到余额、
 * 问上游真实状态、写操作日志。刻意<b>不</b>暴露「直接改订单状态」的方法 ——
 * 所有状态流转都必须先经 {@code compareAndSetOrderStatus*} 抢到变更权（按受影响行数判定），
 * 快照落库只是补齐 remark / 交付项等描述字段。
 *
 * <p>与 {@link ProductMonitorGateway} 同样是 public 接口：集成测试需要在
 * {@code it} 包里包装真实网关（只替换 {@link #fetchUpstreamOrderStatus}），
 * 从而让 CAS / 退款 / 库存归还都落到真实 MySQL 上，只有上游那一跳是桩。
 */
public interface OrderCompensationGateway {

    /** 全部未删除订单快照（持久化优先，内存兜底），供低频关单等任务使用。 */
    List<OrderItem> orderSnapshots();

    /**
     * SQL 限量读取需要真实查单的直充中间态订单；纯内存环境才允许内存筛选。
     *
     * @param deadline 只返回支付时间（无支付时间则创建时间）早于该时刻的订单
     * @param limit 最大返回笔数
     * @param withoutCallback true 时排除下单时已把回调地址交给上游的订单
     */
    List<OrderItem> unsettledOrderCandidates(OffsetDateTime deadline, int limit, boolean withoutCallback);

    /** 补发支付成功但尚未真正提交采购的直充订单。 */
    Optional<OrderItem> recoverUnsubmittedProcurement(OrderItem order);

    /** 是否已启用资金/库存持久化能力（无 DB 的纯内存单测里为 false，补偿任务直接不动手）。 */
    boolean fundsLedgerEnabled();

    /** 库里当前订单状态；订单不存在返回 null。 */
    String currentOrderStatus(String orderNo);

    /** 单前置状态 CAS，true = 本次抢到变更权。 */
    boolean compareAndSetOrderStatus(String orderNo, OrderStatus expected, OrderStatus next);

    /** 双前置状态 CAS（如 PROCURING/DELIVERING 都可推进）。 */
    boolean compareAndSetOrderStatusFromEither(
        String orderNo, OrderStatus expectedA, OrderStatus expectedB, OrderStatus next
    );

    /** 归还下单阶段预扣的库存，走 {@code FundsLedgerStore.restoreStock}。 */
    void restoreReservedStock(OrderItem order);

    /**
     * 退款到余额：退款单 + 加回余额 + CREDIT 流水，同一事务，
     * 幂等键 {@code uk_balance_tx_biz (ORDER_REFUND, orderNo)}。
     */
    void refundToBalance(OrderItem order, String reason);

    /** 保存订单快照并推送实时事件（不改状态，状态已由 CAS 决定）。 */
    void saveAndPublishOrder(OrderItem order);

    /**
     * 问上游这笔订单的真实状态。
     *
     * <p>返回 {@code empty()} 表示<b>这笔单当前无法查询</b>（没有可查渠道 / 供应商缺失 /
     * 适配器不支持查单 / 占位地址）—— 调用方应跳过，不得据此判定失败。
     *
     * @throws SupplierTransportException 上游超时、连接失败、5xx、响应无法解析 ——
     *         结果<b>未知</b>，调用方必须什么都不做，等下一轮（缺陷 A4 的核心不变量）
     * @throws SupplierBusinessException 上游<b>明确</b>拒绝/不存在该单，可据此判失败
     */
    Optional<UpstreamOrderSnapshot> fetchUpstreamOrderStatus(OrderItem order);

    /** 是否已在下单请求中把可用的喜易云回调地址交给上游。 */
    boolean callbackSentOnSubmit(OrderItem order);

    /** 把上游快照合并进订单（渠道尝试记录、交付项、上游订单号、文案），返回待落库的新快照。 */
    OrderItem applyUpstreamSnapshot(OrderItem order, UpstreamOrderSnapshot upstream, OrderStatus nextStatus);

    /** 写一条操作日志（走 AuditService）。 */
    void recordAudit(String action, String resourceType, String resourceId, String remark);
}
