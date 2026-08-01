package com.xiyiyun.shop.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Altcha 自托管 PoW 人机验证：协议闭环。
 *
 * <p>修复背景：此前服务端把 challenge 算成 HMAC-SHA256(key, salt)，而客户端没有 HMAC
 * 密钥、数学上无法复现，widget 只能一直枚举到 worker 超时，线上表现为「一直卡在验证中」。
 *
 * <p>本测试用 Java 复刻 altcha worker（{@code node_modules/altcha/dist/workers/sha.js}，
 * v1 挑战 + cost=1 + counterMode=string）的求解算法，钉住协议必须能闭环：
 * 客户端求解目标是 {@code SHA-256(salt + number) == challenge}。
 */
class AltchaCaptchaProtocolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HMAC_KEY = "unit-test-altcha-hmac-key-0123456789";

    private final AltchaCaptchaService service = new AltchaCaptchaService();

    @Test
    void clientCanSolveChallengeAndServerAcceptsIt() throws Exception {
        JsonNode challenge = MAPPER.readTree(service.generateChallengeJson(HMAC_KEY));

        String salt = challenge.get("salt").asText();
        String target = challenge.get("challenge").asText();
        int maxNumber = challenge.get("maxnumber").asInt();

        assertThat(challenge.get("algorithm").asText()).isEqualTo("SHA-256");
        // salt 必须带 ?expires=<unix秒>，widget 的 createChallengeFromV1 从这里解析过期时间
        assertThat(salt).matches("^[0-9a-f]{32}\\?expires=\\d+$");
        // 回归钉子：challenge 绝不能是 HMAC(salt)，否则客户端永远算不出来
        assertThat(target).isNotEqualTo(hmacSha256Hex(HMAC_KEY, salt));

        long solved = solve(salt, target, maxNumber);
        assertThat(solved).as("客户端必须能在 maxnumber 内枚举出答案").isGreaterThanOrEqualTo(0);

        String ticket = ticket(salt, target, challenge.get("signature").asText(), solved);
        assertThatCode(() -> service.verify(HMAC_KEY, ticket)).doesNotThrowAnyException();
    }

    @Test
    void serverRejectsWrongNumber() {
        // 伪造一个 secretNumber=7 的挑战，然后提交 8：签名合法但 PoW 复现不出 challenge
        Forged forged = forge(7, validExpires());
        String ticket = ticket(forged.salt(), forged.challenge(), forged.signature(), 8);
        assertThatThrownBy(() -> service.verify(HMAC_KEY, ticket))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("人机验证工作量不足，请重试");
    }

    @Test
    void serverRejectsForgedChallengeWithoutValidSignature() {
        Forged forged = forge(7, validExpires());
        // 攻击者自己造 salt/challenge/number 三者自洽，但没有 HMAC 密钥
        String ticket = ticket(forged.salt(), forged.challenge(), "deadbeef", 7);
        assertThatThrownBy(() -> service.verify(HMAC_KEY, ticket))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("人机验证签名无效");
    }

    @Test
    void serverRejectsExpiredChallenge() {
        Forged forged = forge(7, System.currentTimeMillis() / 1000L - 1);
        String ticket = ticket(forged.salt(), forged.challenge(), forged.signature(), 7);
        assertThatThrownBy(() -> service.verify(HMAC_KEY, ticket))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("人机验证已过期，请刷新后重试");
    }

    @Test
    void serverRejectsNumberBeyondMaxNumber() {
        long huge = 200_001L;
        Forged forged = forge(huge, validExpires());
        String ticket = ticket(forged.salt(), forged.challenge(), forged.signature(), huge);
        assertThatThrownBy(() -> service.verify(HMAC_KEY, ticket))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("人机验证数据不合法");
    }

    // ──────────── 客户端侧复刻 ────────────────────────────────────────────

    /**
     * 复刻 altcha 3.2.1 的 workers/sha.js：v1 挑战下 cost=1、counterMode="string"，
     * 求解目标是 SHA-256(saltString + counterString) 的 hex 等于 keyPrefix（即 challenge）。
     * 找不到返回 -1。
     */
    private static long solve(String salt, String challenge, long maxNumber) {
        for (long n = 0; n <= maxNumber; n++) {
            if (challenge.equals(sha256Hex(salt + n))) {
                return n;
            }
        }
        return -1;
    }

    /** 复刻 createPayloadV1：base64(JSON{algorithm, challenge, number, salt, signature, took})。 */
    private static String ticket(String salt, String challenge, String signature, long number) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("algorithm", "SHA-256");
        payload.put("challenge", challenge);
        payload.put("number", number);
        payload.put("salt", salt);
        payload.put("signature", signature);
        payload.put("took", 12);
        try {
            return Base64.getEncoder().encodeToString(MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** 按服务端算法造一个指定 secretNumber / 过期时间的合法挑战，省去暴力枚举。 */
    private static Forged forge(long secretNumber, long expiresSec) {
        String salt = "0123456789abcdef0123456789abcdef?expires=" + expiresSec;
        String challenge = sha256Hex(salt + secretNumber);
        return new Forged(salt, challenge, hmacSha256Hex(HMAC_KEY, challenge));
    }

    private record Forged(String salt, String challenge, String signature) {}

    private static long validExpires() {
        return System.currentTimeMillis() / 1000L + 300;
    }

    private static String sha256Hex(String data) {
        try {
            return hexEncode(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hexEncode(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
