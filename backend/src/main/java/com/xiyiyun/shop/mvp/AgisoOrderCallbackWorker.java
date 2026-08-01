package com.xiyiyun.shop.mvp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "xiyiyun.agiso.enabled", havingValue = "true")
public class AgisoOrderCallbackWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgisoOrderCallbackWorker.class);

    private final AgisoOrderCallbackService callbackService;

    public AgisoOrderCallbackWorker(AgisoOrderCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @Scheduled(
        fixedDelayString = "${xiyiyun.agiso.callback-interval-ms:1000}",
        initialDelayString = "${xiyiyun.agiso.callback-initial-delay-ms:1000}"
    )
    public void dispatchDueCallbacks() {
        try {
            callbackService.dispatchDue();
        } catch (RuntimeException ex) {
            LOGGER.warn("Agiso callback dispatch tick failed: {}", ex.toString());
        }
    }
}
