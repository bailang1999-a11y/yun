package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record RechargeRequestResult(
    String requestNo,
    BigDecimal amount,
    String payMethod,
    String status,
    OffsetDateTime createdAt,
    UserItem profile
) {}
