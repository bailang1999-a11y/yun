package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class WeComRobotSettingTest {
    private static final String WEBHOOK =
        "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=693a91f6-7abc-4bc4-97a0-0ec2aifa5aaa";

    @Test
    void acceptsOnlyTheOfficialWebhookShape() {
        assertThat(WeComRobotSetting.isOfficialWebhook(WEBHOOK)).isTrue();
        assertThat(WeComRobotSetting.isOfficialWebhook(WEBHOOK + "&next=https://evil.example")).isFalse();
        assertThat(WeComRobotSetting.isOfficialWebhook(
            "https://qyapi.weixin.qq.com.evil.example/cgi-bin/webhook/send?key=693a91f6-7abc-4bc4-97a0-0ec2aifa5aaa"
        )).isFalse();
        assertThat(WeComRobotSetting.isOfficialWebhook(
            "http://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=693a91f6-7abc-4bc4-97a0-0ec2aifa5aaa"
        )).isFalse();
    }

    @Test
    void enabledSettingRequiresWebhookAndAtLeastOneEvent() {
        assertThatThrownBy(() -> new WeComRobotSetting(true, "", List.of()).validated())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Webhook");
        assertThatThrownBy(() -> new WeComRobotSetting(true, WEBHOOK, List.of()).validated())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("通知事件");
        assertThat(new WeComRobotSetting(
            true, WEBHOOK, List.of(WeComNotificationEvent.ORDER_CREATED)
        ).validated().enabled()).isTrue();
    }

    @Test
    void publicSystemSettingsNeverExposeTheWebhook() {
        SystemSettingItem setting = new SystemSettingItem(
            "喜易云", "", "", "", "", "", "", "MOCK", true, "TENCENT", false,
            30, true, false, true, "MOBILE", 1L, java.util.Map.of(),
            new WeComRobotSetting(true, WEBHOOK, List.of(WeComNotificationEvent.ORDER_CREATED))
        );

        assertThat(setting.publicView().wecomRobot()).isEqualTo(WeComRobotSetting.disabled());
    }
}
