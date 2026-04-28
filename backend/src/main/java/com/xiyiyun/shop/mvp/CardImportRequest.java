package com.xiyiyun.shop.mvp;

import java.util.List;

public record CardImportRequest(
    List<String> cards,
    String text
) {
}
