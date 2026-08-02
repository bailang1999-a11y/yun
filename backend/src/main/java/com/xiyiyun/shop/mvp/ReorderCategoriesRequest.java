package com.xiyiyun.shop.mvp;

import java.util.List;

public record ReorderCategoriesRequest(List<Long> categoryIds) {
}
