package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MemberCallbackSignatureTest {
    @Test
    void signsTheExactTimestampAndJsonBodyWithTheMemberSecret() {
        assertThat(MemberCallbackSignature.sign(
            "test-secret", 1785427364L, "{\"eventId\":\"evt_1\"}"
        )).isEqualTo("844ce4706b784ef04b63bc488c53cd1cfd0ce216970b1ba5e91a4e77a43d0511");
    }
}
