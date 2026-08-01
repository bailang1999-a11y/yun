package com.xiyiyun.shop.mvp;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 批次8A 三个补偿任务的定时触发器。
 *
 * <p>沿用批次6 的做法：{@code @Scheduled} + Spring 托管的 TaskScheduler，
 * <b>不手写线程</b>。{@code @EnableScheduling} 已在 {@code XiyiyunApplication} 上开启。
 * 单次 tick 抛异常只影响这一次，下一次照常触发；异常会记进日志而不是静默吞掉。
 *
 * <h2>为什么用 fixedDelay 而不是 fixedRate</h2>
 * 轮询与对账都要发上游 HTTP，慢起来是常态。fixedDelay 保证上一轮跑完才开始计时，
 * 不会堆积并发轮次把上游打死。
 *
 * <h2>间隔与上限的取值</h2>
 * 全部可配置，默认值刻意保守（详见各字段的默认值与报告）：
 * <ul>
 *   <li>超时关单 60s 一轮 —— 纯本地 DB 操作，代价低，但没必要更快：支付时限本身是 30 分钟量级；</li>
 *   <li>轮询 120s 一轮、每轮 50 笔 —— 每笔一次上游 HTTP，50×7 家上游的峰值请求量仍很温和；</li>
 *   <li>对账 300s 一轮、每轮 50 笔 —— 只读比对、只写日志，没必要频繁。</li>
 * </ul>
 *
 * <p>{@code xiyiyun.compensation.enabled=false} 可整体关闭（集成测试用它关掉自动触发，
 * 改为在测试里显式调一轮，保证断言确定性）。
 */
@Component
@ConditionalOnProperty(name = "xiyiyun.compensation.enabled", havingValue = "true", matchIfMissing = true)
public class OrderCompensationWorker {
    private static final Logger log = LoggerFactory.getLogger(OrderCompensationWorker.class);

    private final OrderCompensationService compensationService;

    public OrderCompensationWorker(
        InMemoryShopRepository repository,
        @Value("${xiyiyun.compensation.payment-timeout-minutes:30}") int paymentTimeoutMinutes,
        @Value("${xiyiyun.compensation.close-batch-limit:200}") int closeBatchLimit,
        @Value("${xiyiyun.compensation.unsettled-min-age-minutes:10}") int unsettledMinAgeMinutes,
        @Value("${xiyiyun.compensation.poll-batch-limit:50}") int pollBatchLimit,
        @Value("${xiyiyun.compensation.reconcile-min-age-minutes:30}") int reconcileMinAgeMinutes,
        @Value("${xiyiyun.compensation.reconcile-batch-limit:50}") int reconcileBatchLimit
    ) {
        this.compensationService = new OrderCompensationService(
            repository.orderCompensationGateway(),
            new OrderCompensationProperties(
                Duration.ofMinutes(paymentTimeoutMinutes),
                closeBatchLimit,
                Duration.ofMinutes(unsettledMinAgeMinutes),
                pollBatchLimit,
                Duration.ofMinutes(reconcileMinAgeMinutes),
                reconcileBatchLimit
            )
        );
    }

    /** 任务1：超时关单。默认 60s 一轮。 */
    @Scheduled(
        fixedDelayString = "${xiyiyun.compensation.close-interval-ms:60000}",
        initialDelayString = "${xiyiyun.compensation.close-initial-delay-ms:30000}"
    )
    public void closeTimedOutOrders() {
        runTick("close-timed-out", compensationService::closeTimedOutOrders);
    }

    /** 任务2：轮询未终结订单。默认 120s 一轮。 */
    @Scheduled(
        fixedDelayString = "${xiyiyun.compensation.poll-interval-ms:120000}",
        initialDelayString = "${xiyiyun.compensation.poll-initial-delay-ms:60000}"
    )
    public void pollUnsettledOrders() {
        runTick("poll-unsettled", compensationService::pollUnsettledOrders);
    }

    /** 无可用上游回调时，每 10 秒真实查询一次进行中的直充订单。 */
    @Scheduled(
        fixedDelayString = "${xiyiyun.compensation.fast-poll-interval-ms:10000}",
        initialDelayString = "${xiyiyun.compensation.fast-poll-initial-delay-ms:10000}"
    )
    public void pollUnsettledOrdersWithoutCallback() {
        runTick("fast-poll-without-callback", compensationService::pollUnsettledOrdersWithoutCallback);
    }

    /** 任务3：对账（只报不改）。默认 300s 一轮。 */
    @Scheduled(
        fixedDelayString = "${xiyiyun.compensation.reconcile-interval-ms:300000}",
        initialDelayString = "${xiyiyun.compensation.reconcile-initial-delay-ms:120000}"
    )
    public void reconcileUnsettledOrders() {
        runTick("reconcile", compensationService::reconcileUnsettledOrders);
    }

    private void runTick(String name, java.util.function.Supplier<OrderCompensationService.CompensationResult> task) {
        try {
            OrderCompensationService.CompensationResult result = task.get();
            if (result.processed() > 0 || result.failed() > 0) {
                log.info("compensation {} tick: scanned={} processed={} changed={} skipped={} failed={}",
                    name, result.scanned(), result.processed(), result.changed(), result.skipped(), result.failed());
            }
        } catch (RuntimeException ex) {
            // 整轮级兜底：本次 tick 失败不影响下一次触发，但必须留下日志
            log.warn("compensation {} tick failed: {}", name, ex.toString());
        }
    }
}
