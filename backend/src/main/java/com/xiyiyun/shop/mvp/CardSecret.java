package com.xiyiyun.shop.mvp;

import java.time.OffsetDateTime;

public record CardSecret(
    Long id,
    Long goodsId,
    String cardNo,
    String secret,
    String content,
    String preview,
    String status,
    String orderNo,
    OffsetDateTime importedAt,
    OffsetDateTime deliveredAt
) {
    public CardSecret delivered(String nextOrderNo) {
        return new CardSecret(
            id,
            goodsId,
            cardNo,
            secret,
            content,
            preview,
            "USED",
            nextOrderNo,
            importedAt,
            OffsetDateTime.now()
        );
    }
}
