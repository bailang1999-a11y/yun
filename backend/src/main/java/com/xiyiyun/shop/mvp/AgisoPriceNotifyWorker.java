package com.xiyiyun.shop.mvp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "xiyiyun.agiso.enabled", havingValue = "true")
public class AgisoPriceNotifyWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgisoPriceNotifyWorker.class);

    private final AgisoPriceSubscriptionService subscriptionService;

    public AgisoPriceNotifyWorker(AgisoPriceSubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @Scheduled(
        fixedDelayString = "${xiyiyun.agiso.price-notify-interval-ms:5000}",
        initialDelayString = "${xiyiyun.agiso.price-notify-initial-delay-ms:5000}"
    )
    public void dispatchDueNotifications() {
        try {
            subscriptionService.dispatchDue();
        } catch (RuntimeException ex) {
            LOGGER.warn("Agiso price notification tick failed: {}", ex.toString());
        }
    }
}
