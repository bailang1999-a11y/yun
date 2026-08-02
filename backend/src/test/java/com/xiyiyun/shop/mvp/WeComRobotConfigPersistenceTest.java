package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xiyiyun.shop.persistence.CardCipherService;
import com.xiyiyun.shop.persistence.ConfigPersistenceStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WeComRobotConfigPersistenceTest {
    private static final String WEBHOOK =
        "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=693a91f6-7abc-4bc4-97a0-0ec2aifa5aaa";

    @Test
    void savedRobotConfigurationSurvivesAConfigServiceRestart() {
        Map<String, String> database = new LinkedHashMap<>();
        CardCipherService cipher = new CardCipherService("wecom-config-test-secret");
        ConfigPersistenceStore store = mock(ConfigPersistenceStore.class);
        when(store.systemSettings()).thenAnswer(invocation -> new LinkedHashMap<>(database));
        doAnswer(invocation -> {
            SystemSettingItem setting = invocation.getArgument(0);
            CardCipherService.EncryptedCard encrypted = cipher.encrypt(setting.wecomRobot().webhookUrl());
            database.put("wecom.robot.enabled", String.valueOf(setting.wecomRobot().enabled()));
            database.put("wecom.robot.events", "DELIVERY_SUCCEEDED,DELIVERY_FAILED");
            database.put("wecom.robot.webhook.ciphertext", java.util.Base64.getEncoder().encodeToString(encrypted.ciphertext()));
            database.put("wecom.robot.webhook.nonce", java.util.Base64.getEncoder().encodeToString(encrypted.nonce()));
            database.put("wecom.robot.webhook.keyVersion", encrypted.keyVersion());
            return null;
        }).when(store).saveSystemSetting(any());
        when(store.decryptSecretFromSetting(any(), any(), any())).thenAnswer(invocation -> cipher.decrypt(
            java.util.Base64.getDecoder().decode(invocation.<String>getArgument(0)),
            java.util.Base64.getDecoder().decode(invocation.<String>getArgument(1)),
            invocation.getArgument(2)
        ));

        ConfigService before = service(store);
        before.updateSystemSetting(request(new WeComRobotSetting(
            true, WEBHOOK,
            List.of(WeComNotificationEvent.DELIVERY_SUCCEEDED, WeComNotificationEvent.DELIVERY_FAILED)
        )));

        ConfigService afterRestart = service(store);
        afterRestart.loadPersistentSystemSetting();

        assertThat(afterRestart.systemSetting().wecomRobot()).isEqualTo(new WeComRobotSetting(
            true, WEBHOOK,
            List.of(WeComNotificationEvent.DELIVERY_SUCCEEDED, WeComNotificationEvent.DELIVERY_FAILED)
        ));
    }

    private ConfigService service(ConfigPersistenceStore store) {
        return new ConfigService(
            store, new AuditService(null), defaultSetting(), value -> value, value -> value
        );
    }

    private UpdateSystemSettingRequest request(WeComRobotSetting setting) {
        return new UpdateSystemSettingRequest(
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, setting
        );
    }

    private SystemSettingItem defaultSetting() {
        return new SystemSettingItem(
            "喜易云", "", "", "", "", "", "", "MOCK", true, "TENCENT", false,
            30, true, false, true, "MOBILE", 1L, Map.of(), WeComRobotSetting.disabled()
        );
    }
}
