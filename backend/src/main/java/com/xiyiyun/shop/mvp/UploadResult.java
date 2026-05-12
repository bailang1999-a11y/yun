package com.xiyiyun.shop.mvp;

public record UploadResult(
    String url,
    String filename,
    Long size,
    String contentType
) {
}
