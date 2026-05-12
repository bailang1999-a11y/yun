package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;

public record RechargeRequest(
    BigDecimal amount,
    String payMethod,
    String remark
) {}
