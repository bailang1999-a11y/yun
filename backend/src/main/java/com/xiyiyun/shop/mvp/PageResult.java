package com.xiyiyun.shop.mvp;

import java.util.List;

public record PageResult<T>(
    List<T> items,
    long total,
    int page,
    int pageSize
) {
}
