package com.xiyiyun.shop.mvp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "xiyiyun.member-callback.enabled", havingValue = "true", matchIfMissing = true)
public class MemberOrderCallbackWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemberOrderCallbackWorker.class);

    private final MemberOrderCallbackService callbackService;

    public MemberOrderCallbackWorker(MemberOrderCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @Scheduled(
        fixedDelayString = "${xiyiyun.member-callback.interval-ms:1000}",
        initialDelayString = "${xiyiyun.member-callback.initial-delay-ms:1000}"
    )
    public void dispatchDueCallbacks() {
        try {
            callbackService.dispatchDue();
        } catch (RuntimeException ex) {
            LOGGER.warn("Member callback dispatch tick failed: {}", ex.toString());
        }
    }
}
