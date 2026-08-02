package com.xiyiyun.shop.mvp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "xiyiyun.wecom-robot.enabled", havingValue = "true", matchIfMissing = true)
public class WeComRobotNotificationWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(WeComRobotNotificationWorker.class);

    private final WeComRobotNotificationService service;

    public WeComRobotNotificationWorker(WeComRobotNotificationService service) {
        this.service = service;
    }

    @Scheduled(
        fixedDelayString = "${xiyiyun.wecom-robot.interval-ms:1000}",
        initialDelayString = "${xiyiyun.wecom-robot.initial-delay-ms:1000}"
    )
    public void dispatchDueNotifications() {
        try {
            service.dispatchDue();
        } catch (RuntimeException ex) {
            LOGGER.warn("WeCom robot dispatch tick failed: {}", ex.toString());
        }
    }
}
