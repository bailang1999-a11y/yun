package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 商品监控的定时触发器。
 *
 * <h2>批次6：从手写守护线程换成 {@code @Scheduled}</h2>
 * 原实现是 {@code @PostConstruct} 里 {@code new Thread(...).setDaemon(true)} 的死循环，三个硬问题：
 * <ol>
 *   <li><b>没有优雅停机</b>：daemon 线程在 JVM 退出时被直接砍掉，正在进行的一轮扫描
 *       写状态写一半就没了；{@code @PreDestroy} 只 interrupt，不等它收尾。</li>
 *   <li><b>异常会让监控永久停摆</b>：{@code runLoop} 只 catch 了 {@code RuntimeException}。
 *       任何 {@code Error} 或漏网异常都会终结整个线程 —— 进程还活着、接口还通，
 *       但监控从此再也不扫，而且<b>没有任何日志</b>。</li>
 *   <li><b>不受 Spring 生命周期管理</b>：无法参与容器停机顺序，也没有统一的线程池与可观测性。</li>
 * </ol>
 * 换成 {@code @Scheduled} 后：由 Spring 的 {@code TaskScheduler} 托管（停机时容器会等任务收尾），
 * 单次 tick 抛异常只影响这一次、下一次照常触发，异常会记进日志而不是静默吞掉。
 *
 * <p>{@code @EnableScheduling} 在 {@code XiyiyunApplication} 上开启（批次6 新增，原项目未开启）。
 *
 * <p>{@code fixedDelay = 1000} 保持与原 {@code Thread.sleep(1000L)} 一致的节奏；
 * 用 fixedDelay 而非 fixedRate，确保上一轮跑完才计时，扫描变慢时不会堆积并发扫描。
 * 真正的扫描频率仍由每个渠道自己的 {@code nextScanAt} 决定（默认 180s）。
 */
@Component
public class ProductMonitorWorker {
    private static final Logger log = LoggerFactory.getLogger(ProductMonitorWorker.class);

    private final InMemoryShopRepository repository;

    public ProductMonitorWorker(InMemoryShopRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelay = 1000L, initialDelay = 1000L)
    public void scanDueChannels() {
        try {
            List<Long> dueChannels = repository.dueProductMonitorChannelIds(OffsetDateTime.now());
            for (Long channelId : dueChannels) {
                try {
                    repository.scanProductMonitorChannel(channelId, false);
                } catch (RuntimeException ex) {
                    // 单个渠道失败不该带走整轮：记下来，继续扫下一个。
                    log.warn("product monitor scan failed for channel {}: {}", channelId, ex.toString());
                }
            }
        } catch (RuntimeException ex) {
            // 原实现在这里静默 sleep 后重试，异常从不留痕。现在至少能在日志里看见。
            log.warn("product monitor tick failed: {}", ex.toString());
        }
    }
}
