package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.persistence.WeComRobotDeliveryTaskStore;
import com.xiyiyun.shop.persistence.entity.WeComRobotDeliveryTaskEntity;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WeComRobotNotificationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WeComRobotNotificationService.class);
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final WeComRobotDeliveryTaskStore taskStore;
    private final WeComRobotClient client;
    private final InMemoryShopRepository repository;

    public WeComRobotNotificationService(
        WeComRobotDeliveryTaskStore taskStore,
        WeComRobotClient client,
        InMemoryShopRepository repository
    ) {
        this.taskStore = taskStore;
        this.client = client;
        this.repository = repository;
    }

    void dispatchDue() {
        OffsetDateTime now = OffsetDateTime.now();
        for (WeComRobotDeliveryTaskEntity task : taskStore.findDue(now, BATCH_SIZE)) {
            try {
                dispatch(task, now, false);
            } catch (RuntimeException ex) {
                LOGGER.warn("WeCom robot task {} processing failed: {}", task.getId(), ex.toString());
            }
        }
    }

    public String sendTest() {
        WeComRobotSetting setting = repository.systemSetting().wecomRobot().validated();
        if (!setting.enabled()) {
            throw new IllegalArgumentException("请先启用企业微信群机器人并保存设置");
        }
        WeComRobotDeliveryTaskEntity task = taskStore.registerTest();
        dispatch(task, OffsetDateTime.now(), true);
        return "ok";
    }

    public List<WeComRobotDeliveryItem> latestDeliveries(int limit) {
        return taskStore.latest(limit).stream().map(this::toItem).toList();
    }

    private void dispatch(WeComRobotDeliveryTaskEntity task, OffsetDateTime now, boolean propagateFailure) {
        if (!taskStore.claim(task.getId(), now, now.plus(LEASE))) return;
        try {
            WeComRobotSetting setting = repository.systemSetting().wecomRobot().validated();
            if (!setting.enabled()) {
                taskStore.markCancelled(task.getId(), "机器人已停用");
                return;
            }
            if (!"TEST".equals(task.getEventType())
                && !setting.accepts(WeComNotificationEvent.valueOf(task.getEventType()))) {
                taskStore.markCancelled(task.getId(), "通知事件已关闭");
                return;
            }
            client.sendMarkdown(setting.webhookUrl(), task.getMarkdownContent());
            taskStore.markSent(task.getId(), OffsetDateTime.now());
        } catch (RuntimeException ex) {
            int attempts = task.getAttemptCount() == null ? 1 : task.getAttemptCount() + 1;
            if (attempts >= MAX_ATTEMPTS) {
                taskStore.markDead(task.getId(), message(ex));
            } else {
                taskStore.retry(task.getId(), OffsetDateTime.now().plusSeconds(retryDelay(attempts)), message(ex));
            }
            LOGGER.warn("WeCom robot delivery failed for {}: {}", task.getOrderNo(), ex.toString());
            if (propagateFailure) throw new IllegalStateException(message(ex), ex);
        }
    }

    private WeComRobotDeliveryItem toItem(WeComRobotDeliveryTaskEntity task) {
        String status = switch (task.getState() == null ? "" : task.getState()) {
            case "SENT" -> "SUCCESS";
            case "DEAD", "CANCELLED" -> "FAILED";
            default -> "PENDING";
        };
        return new WeComRobotDeliveryItem(
            task.getId(), task.getEventType(), status, task.getOrderNo(),
            task.getAttemptCount() == null ? 0 : task.getAttemptCount(), task.getLastError(), task.getCreatedAt()
        );
    }

    private long retryDelay(int attempts) {
        int exponent = Math.max(0, Math.min(attempts - 1, 8));
        return Math.min(300L, 1L << exponent);
    }

    private String message(RuntimeException ex) {
        String value = ex.getMessage() == null ? "企业微信发送失败" : ex.getMessage().trim();
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
