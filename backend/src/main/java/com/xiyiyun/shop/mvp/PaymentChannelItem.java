package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record PaymentChannelItem(
    Long id,
    String code,
    String name,
    String type,
    List<String> terminals,
    String status,
    Integer sort,
    Map<String, String> config,
    String remark,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
