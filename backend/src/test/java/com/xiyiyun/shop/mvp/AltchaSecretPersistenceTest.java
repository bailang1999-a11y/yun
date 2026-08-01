package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiyiyun.shop.persistence.ConfigPersistenceStore;
import com.xiyiyun.shop.persistence.ConfigTableStore;
import com.xiyiyun.shop.persistence.entity.CaptchaSettingRecordEntity;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 钉住 Altcha HMAC 密钥的持久化往返。
 *
 * <p>背景：{@code hmac_key} 命中敏感键判定（含 {@code hmac}）后被加密，只出现在
 * {@code altchaConfigSecrets} 里，{@code altchaConfig} 只剩空对象。曾经
 * {@link ConfigService#saveCaptchaSettingJson} 只落 {@code altchaConfig}、读侧也只读
 * public 列，于是密钥在保存瞬间被静默丢弃：{@code generic_config_public} 写成
 * {@code {}}、{@code generic_config_secrets} 保持 NULL，后台反复保存都没用，
 * 前台一律报「Altcha HMAC 密钥未配置」。
 *
 * <p>这类 Bug 的成因是「敏感键改造」和「持久化列映射」分处两个类，改了一边忘了另一边。
 * 所以这里直接钉往返，而不是钉某一侧的实现细节。
 */
class AltchaSecretPersistenceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConfigTableStore tableStore = mock(ConfigTableStore.class);
    private final ConfigPersistenceStore persistenceStore = mock(ConfigPersistenceStore.class);
    private final AuditService auditService = mock(AuditService.class);

    private ConfigService newService() {
        when(persistenceStore.configTables()).thenReturn(tableStore);
        return new ConfigService(persistenceStore, auditService, null, null, null);
    }

    /** 保存时密文必须落到 secrets 列，否则密钥无处存放。 */
    @Test
    void saveWritesEncryptedHmacKeyIntoSecretsColumn() {
        ConfigService service = newService();

        service.saveCaptchaSettingJson(payloadWithSecret("cipher-text-value", "nonce-value"));

        CaptchaSettingRecordEntity saved = captureSaved();
        assertThat(saved.getGenericConfigSecrets())
            .as("加密后的 hmac_key 必须落库，否则保存动作等于丢弃密钥")
            .isNotNull()
            .contains("hmac_key")
            .contains("cipher-text-value")
            .contains("nonce-value");
    }

    /** 读取时 secrets 列必须回填成 altchaConfigSecrets，否则解密侧拿不到密文。 */
    @Test
    void readExposesSecretsColumnAsAltchaConfigSecrets() throws Exception {
        ConfigService service = newService();
        CaptchaSettingRecordEntity row = new CaptchaSettingRecordEntity();
        row.setProvider("ALTCHA");
        row.setGenericConfigPublic("{}");
        row.setGenericConfigSecrets(
            "{\"hmac_key\":{\"ciphertext\":\"cipher-text-value\",\"nonce\":\"nonce-value\"}}");
        when(tableStore.captchaSetting()).thenReturn(Optional.of(row));

        JsonNode node = MAPPER.readTree(service.captchaSettingJson());

        assertThat(node.has("altchaConfigSecrets"))
            .as("读侧漏掉这个字段就是「已保存却提示未配置」的直接原因")
            .isTrue();
        JsonNode secret = node.path("altchaConfigSecrets").path("hmac_key");
        assertThat(secret.path("ciphertext").asText()).isEqualTo("cipher-text-value");
        assertThat(secret.path("nonce").asText()).isEqualTo("nonce-value");
    }

    /** 完整往返：写进去的密文必须能原样读回来。 */
    @Test
    void encryptedHmacKeySurvivesSaveThenLoad() throws Exception {
        ConfigService service = newService();
        service.saveCaptchaSettingJson(payloadWithSecret("round-trip-cipher", "round-trip-nonce"));
        CaptchaSettingRecordEntity saved = captureSaved();
        when(tableStore.captchaSetting()).thenReturn(Optional.of(saved));

        JsonNode reloaded = MAPPER.readTree(service.captchaSettingJson())
            .path("altchaConfigSecrets").path("hmac_key");

        assertThat(reloaded.path("ciphertext").asText()).isEqualTo("round-trip-cipher");
        assertThat(reloaded.path("nonce").asText()).isEqualTo("round-trip-nonce");
        assertThat(reloaded.path("ciphertext").asText())
            .as("密文不应等于明文，加密环节必须真的生效过")
            .isNotEqualTo("plain-hmac-key");
    }

    /** GENERIC provider 不能被 Altcha 的复用逻辑覆盖掉自己的密文列。 */
    @Test
    void genericProviderSecretsAreNotClobberedWhenAltchaAbsent() {
        ConfigService service = newService();
        String json = "{\"provider\":\"GENERIC\","
            + "\"genericConfig\":{\"endpoint\":\"https://example.test\"},"
            + "\"genericConfigSecrets\":{\"api_key\":{\"ciphertext\":\"g-cipher\",\"nonce\":\"g-nonce\"}}}";

        service.saveCaptchaSettingJson(json);

        CaptchaSettingRecordEntity saved = captureSaved();
        assertThat(saved.getGenericConfigSecrets()).contains("g-cipher");
        assertThat(saved.getGenericConfigPublic()).contains("example.test");
    }

    private String payloadWithSecret(String ciphertext, String nonce) {
        return "{\"enabled\":true,\"h5LoginEnabled\":true,\"provider\":\"ALTCHA\","
            + "\"altchaConfig\":{},"
            + "\"altchaConfigSecrets\":{\"hmac_key\":{\"ciphertext\":\"" + ciphertext
            + "\",\"nonce\":\"" + nonce + "\"}}}";
    }

    private CaptchaSettingRecordEntity captureSaved() {
        ArgumentCaptor<CaptchaSettingRecordEntity> captor =
            ArgumentCaptor.forClass(CaptchaSettingRecordEntity.class);
        verify(tableStore).saveCaptchaSetting(captor.capture());
        return captor.getValue();
    }
}
