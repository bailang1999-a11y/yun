package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Altcha PoW（工作量证明）人机验证。
 *
 * <h2>为什么选 Altcha</h2>
 * <ul>
 *   <li>完全自托管，不依赖任何境外服务，对大陆用户零延迟；</li>
 *   <li>用户无感——浏览器在后台算一次 SHA-256 哈希，通常 &lt;1s 完成；</li>
 *   <li>服务端仅需 HMAC-SHA256 + SHA-256，无外部 HTTP 请求。</li>
 * </ul>
 *
 * <h2>协议概要（Altcha 官方 v1）</h2>
 * <ol>
 *   <li>服务端随机取 secretNumber ∈ [0, maxnumber]，salt = 随机16字节hex + "?expires=" + unix秒，
 *       challenge = SHA-256(salt + secretNumber)，signature = HMAC-SHA256(key, challenge)。</li>
 *   <li>客户端枚举 number = 0..maxnumber，找到第一个使
 *       SHA-256(salt + number) == challenge 的 number（即倒推出 secretNumber）。</li>
 *   <li>客户端提交 base64(JSON{algorithm, challenge, number, salt, signature, took})。</li>
 *   <li>服务端验证：① signature = HMAC(challenge)，证明挑战由本服务签发；
 *       ② challenge = SHA-256(salt + number)，证明客户端真的算出了 secretNumber；
 *       ③ salt 里的 expires 未过期。</li>
 * </ol>
 *
 * <p>challenge 必须是 SHA-256(salt + secretNumber)，不能是 HMAC(salt)：客户端没有 HMAC
 * 密钥，无法复现 HMAC 结果，只会一直枚举到 worker 超时，表现为「卡在验证中」。
 */
@Component
public class AltchaCaptchaService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALGORITHM = "SHA-256";
    /** 挑战有效时间（毫秒）。 */
    private static final long CHALLENGE_TTL_MS = 5 * 60 * 1000L;
    /**
     * secretNumber 的上界，同时作为下发给客户端的 maxnumber。
     * 客户端从 0 顺序枚举，平均要算 MAX_NUMBER/2 次 SHA-256；按单线程约 76k hash/s 估算，
     * 20 万对应平均 ≈1.3s，移动端单核也能接受。
     */
    private static final int MAX_NUMBER = 200_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    // ──────────── 生成挑战 ──────────────────────────────────────────────

    /**
     * 生成挑战 payload，直接作为 HTTP 响应体返回给 Altcha widget。
     *
     * @param hmacKey 配置中的 HMAC 密钥（至少 32 位）
     */
    public String generateChallengeJson(String hmacKey) {
        requireKey(hmacKey);
        byte[] nonceBytes = new byte[16];
        RANDOM.nextBytes(nonceBytes);
        // salt = nonce?expires=<unix秒>，widget 会解析 expires 做本地过期判断，
        // 服务端也从这里取过期时间，无需额外存储挑战。
        long expiresSec = (System.currentTimeMillis() + CHALLENGE_TTL_MS) / 1000L;
        String salt = hexEncode(nonceBytes) + "?expires=" + expiresSec;
        // 客户端要枚举出的目标数字
        int secretNumber = RANDOM.nextInt(MAX_NUMBER + 1);
        String challenge = hexEncode(sha256((salt + secretNumber).getBytes(StandardCharsets.UTF_8)));
        String signature = hmacSha256Hex(hmacKey, challenge);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("algorithm", ALGORITHM);
        payload.put("challenge", challenge);
        payload.put("maxnumber", MAX_NUMBER);
        payload.put("salt", salt);
        payload.put("signature", signature);
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Altcha challenge 序列化失败", ex);
        }
    }

    // ──────────── 后台「测试配置」 ────────────────────────────────────────

    /**
     * 后台「测试配置」按钮的自检。
     *
     * <p>Altcha 是自托管的，没有第三方接口可打，所以这里不做网络请求，
     * 而是真的走一遍「生成挑战 → 本地解析」，确认密钥可用、payload 结构完整。
     * 这样「测试通过」就等价于「前端能拿到可用的挑战」，不是一句空话。
     */
    public String testSetting(Map<String, String> config) {
        String hmacKey = config == null ? "" : config.get("hmac_key");
        requireKey(hmacKey);
        String json = generateChallengeJson(hmacKey);
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!StringUtils.hasText(text(node, "challenge")) || !StringUtils.hasText(text(node, "signature"))) {
                throw new IllegalStateException("Altcha 挑战生成异常：缺少 challenge 或 signature");
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Altcha 挑战生成异常", ex);
        }
        return "Altcha 自检通过：HMAC 密钥可用，挑战可正常下发（自托管，无需外部服务）。";
    }

    // ──────────── 验证客户端答案 ──────────────────────────────────────────

    /**
     * 验证 Altcha widget 提交的 ticket（base64 JSON）。
     *
     * @param hmacKey 配置中的 HMAC 密钥
     * @param ticket  Altcha widget 提交的 base64 payload
     */
    public void verify(String hmacKey, String ticket) {
        requireKey(hmacKey);
        if (!StringUtils.hasText(ticket)) {
            throw new IllegalArgumentException("请先完成人机验证");
        }

        JsonNode node;
        try {
            byte[] decoded = Base64.getDecoder().decode(ticket.trim());
            node = MAPPER.readTree(decoded);
        } catch (Exception ex) {
            throw new IllegalArgumentException("人机验证数据格式不正确");
        }

        String algorithm = text(node, "algorithm");
        String challenge = text(node, "challenge");
        String salt = text(node, "salt");
        String signature = text(node, "signature");
        long number = node.path("number").asLong(-1);

        if (!ALGORITHM.equals(algorithm)) {
            throw new IllegalArgumentException("人机验证算法不受支持：" + algorithm);
        }
        if (!StringUtils.hasText(challenge) || !StringUtils.hasText(salt) || !StringUtils.hasText(signature) || number < 0) {
            throw new IllegalArgumentException("人机验证数据不完整");
        }

        // ① 验 signature
        String expectedSig = hmacSha256Hex(hmacKey, challenge);
        if (!MessageDigest.isEqual(expectedSig.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("人机验证签名无效");
        }

        // ② 验过期（salt 格式：nonce?expires=<unix秒>）
        long expiresSec = parseExpires(salt);
        if (System.currentTimeMillis() > expiresSec * 1000L) {
            throw new IllegalArgumentException("人机验证已过期，请刷新后重试");
        }

        // ③ 验 PoW：客户端提交的 number 必须能复现 challenge，即 SHA-256(salt + number) == challenge。
        // signature 已在 ① 证明 challenge 出自本服务端，所以这一步等价于「确实算出了那个 secretNumber」。
        if (number > MAX_NUMBER) {
            throw new IllegalArgumentException("人机验证数据不合法");
        }
        String computed = hexEncode(sha256((salt + number).getBytes(StandardCharsets.UTF_8)));
        if (!MessageDigest.isEqual(computed.getBytes(StandardCharsets.UTF_8), challenge.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("人机验证工作量不足，请重试");
        }
    }

    /** 从 salt 中取 {@code ?expires=<unix秒>}；缺失或不合法都视为无效挑战。 */
    private static long parseExpires(String salt) {
        int q = salt.indexOf("?expires=");
        if (q < 0) {
            throw new IllegalArgumentException("人机验证 salt 格式不正确");
        }
        String value = salt.substring(q + "?expires=".length());
        int amp = value.indexOf('&');
        if (amp >= 0) {
            value = value.substring(0, amp);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("人机验证 salt 时间戳不合法");
        }
    }

    // ──────────── 内部工具 ──────────────────────────────────────────────

    private static void requireKey(String key) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException("Altcha HMAC 密钥未配置，请在后台验证码设置中填写");
        }
    }

    private static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hexEncode(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 失败", ex);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }
}
