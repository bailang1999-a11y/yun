package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;

public record SmsVerificationCode(
    String key,
    String code,
    OffsetDateTime expiresAt,
    OffsetDateTime sentAt,
    int attempts,
    boolean used
) {
}
