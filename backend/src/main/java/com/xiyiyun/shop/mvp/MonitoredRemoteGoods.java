package com.xiyiyun.shop.mvp;

import java.math.BigDecimal;

record MonitoredRemoteGoods(
    String title,
    BigDecimal price,
    Integer stock,
    String status
) {
}
