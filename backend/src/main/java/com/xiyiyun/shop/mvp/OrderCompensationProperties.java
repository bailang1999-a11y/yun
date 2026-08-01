package com.xiyiyun.shop.mvp;

import java.time.Duration;

/**
 * 批次8A 补偿任务的可调参数。全部有保守默认值，生产可用环境变量覆盖。
 *
 * @param paymentTimeout        UNPAID/CREATED 订单的支付时限，超过即取消并归还预扣库存
 * @param closeBatchLimit       超时关单单轮最多处理笔数
 * @param unsettledMinAge       中间态订单至少停留多久才去问上游（给正常回调留出到达窗口）
 * @param pollBatchLimit        轮询单轮最多处理笔数（每笔都要发一次上游 HTTP）
 * @param reconcileMinAge       对账只看停留超过该时长的中间态订单
 * @param reconcileBatchLimit   对账单轮最多比对笔数
 */
public record OrderCompensationProperties(
    Duration paymentTimeout,
    int closeBatchLimit,
    Duration unsettledMinAge,
    int pollBatchLimit,
    Duration reconcileMinAge,
    int reconcileBatchLimit
) {
    public static final Duration DEFAULT_PAYMENT_TIMEOUT = Duration.ofMinutes(30);
    public static final int DEFAULT_CLOSE_BATCH_LIMIT = 200;
    public static final Duration DEFAULT_UNSETTLED_MIN_AGE = Duration.ofMinutes(10);
    public static final int DEFAULT_POLL_BATCH_LIMIT = 50;
    public static final Duration DEFAULT_RECONCILE_MIN_AGE = Duration.ofMinutes(30);
    public static final int DEFAULT_RECONCILE_BATCH_LIMIT = 50;

    public OrderCompensationProperties {
        paymentTimeout = positive(paymentTimeout, DEFAULT_PAYMENT_TIMEOUT);
        unsettledMinAge = positive(unsettledMinAge, DEFAULT_UNSETTLED_MIN_AGE);
        reconcileMinAge = positive(reconcileMinAge, DEFAULT_RECONCILE_MIN_AGE);
        closeBatchLimit = positive(closeBatchLimit, DEFAULT_CLOSE_BATCH_LIMIT);
        pollBatchLimit = positive(pollBatchLimit, DEFAULT_POLL_BATCH_LIMIT);
        reconcileBatchLimit = positive(reconcileBatchLimit, DEFAULT_RECONCILE_BATCH_LIMIT);
    }

    public static OrderCompensationProperties defaults() {
        return new OrderCompensationProperties(
            DEFAULT_PAYMENT_TIMEOUT,
            DEFAULT_CLOSE_BATCH_LIMIT,
            DEFAULT_UNSETTLED_MIN_AGE,
            DEFAULT_POLL_BATCH_LIMIT,
            DEFAULT_RECONCILE_MIN_AGE,
            DEFAULT_RECONCILE_BATCH_LIMIT
        );
    }

    /** 测试用：把三个时间阈值一起压到很小，避免测试等待真实分钟数。 */
    public OrderCompensationProperties withAges(Duration payment, Duration unsettled, Duration reconcile) {
        return new OrderCompensationProperties(
            payment, closeBatchLimit, unsettled, pollBatchLimit, reconcile, reconcileBatchLimit
        );
    }

    public OrderCompensationProperties withLimits(int close, int poll, int reconcile) {
        return new OrderCompensationProperties(
            paymentTimeout, close, unsettledMinAge, poll, reconcileMinAge, reconcile
        );
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    private static int positive(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }
}
