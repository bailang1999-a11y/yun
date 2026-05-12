package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;

public record UserFundAdjustRequest(
    String accountType,
    String direction,
    BigDecimal amount,
    String remark
) {
}
