package com.xiyiyun.shop.mvp;

import java.util.List;
import java.util.Map;

public record PaymentChannelRequest(
    String code,
    String name,
    String type,
    List<String> terminals,
    String status,
    Integer sort,
    Map<String, String> config,
    String remark
) {
}
