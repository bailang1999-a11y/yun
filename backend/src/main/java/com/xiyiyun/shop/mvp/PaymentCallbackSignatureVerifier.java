package com.xiyiyun.shop.mvp;

import com.xiyiyun.shop.security.RedisSecurityStateStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 支付回调验签（批次5 / B1）。
 *
 * <h2>旧实现的问题</h2>
 * 签名串只有 {@code provider|paymentNo|orderNo|status|channelTradeNo}，<b>不含金额、无时效、无重放防护</b>：
 * <ol>
 *   <li>金额不在签名内 → 拿一个合法签名把 amount 改小即可少付；</li>
 *   <li>无 timestamp → 签名永久有效，抓一次包可以无限期复用；</li>
 *   <li>无 nonce → 同一份合法报文可以重放。</li>
 * </ol>
 *
 * <h2>新签名串规格（v2，交付上游）</h2>
 * <pre>
 * payload = provider + "\n" + paymentNo + "\n" + orderNo + "\n" + status + "\n"
 *         + channelTradeNo + "\n" + amount + "\n" + timestamp + "\n" + nonce
 * signature = lowercase(hex(HMAC-SHA256(callbackSecret, UTF8(payload))))
 * </pre>
 * 规则：字段顺序固定、分隔符为单个 {@code \n}（LF）、<b>空值参与拼接但表示为空串</b>（不跳过、不排序）、
 * 首尾空白先 trim；{@code amount} 用 {@code BigDecimal#toPlainString}（不使用科学计数法），
 * {@code timestamp} 为毫秒级 epoch 十进制字符串。
 *
 * <h2>过渡期开关</h2>
 * {@code xiyiyun.payment.callback-strict-signature}（默认 {@code false}）：
 * <ul>
 *   <li>{@code false}（过渡期）：三个新字段<b>全缺</b>时按 v1 规格验签，便于已对接上游平滑迁移；
 *       但只要报文里出现任意一个新字段，就必须三者齐备并按 v2 校验（含时间窗 + nonce）。</li>
 *   <li>{@code true}（强制）：缺字段直接拒绝，只认 v2。prod profile 由
 *       {@code ProductionSafetyConfigValidator} 强制要求为 true。</li>
 * </ul>
 */
@Component
public class PaymentCallbackSignatureVerifier {
    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackSignatureVerifier.class);
    /** 本地兜底的 nonce 缓存上限，避免 Redis 不可用时无界增长。 */
    private static final int LOCAL_NONCE_CAPACITY = 20_000;

    private final String callbackSecret;
    private final boolean strictSignature;
    private final Duration timestampTolerance;
    private final RedisSecurityStateStore securityStateStore;
    private final Map<String, Long> localNonceExpiresAt = new ConcurrentHashMap<>();

    @Autowired
    public PaymentCallbackSignatureVerifier(
        @Value("${xiyiyun.payment.callback-secret:xiyiyun_mock_payment_secret}") String callbackSecret,
        @Value("${xiyiyun.payment.callback-strict-signature:false}") boolean strictSignature,
        @Value("${xiyiyun.payment.callback-timestamp-tolerance-seconds:300}") long timestampToleranceSeconds,
        ObjectProvider<RedisSecurityStateStore> securityStateStoreProvider
    ) {
        this(
            callbackSecret,
            strictSignature,
            timestampToleranceSeconds,
            securityStateStoreProvider == null ? null : securityStateStoreProvider.getIfAvailable()
        );
    }

    public PaymentCallbackSignatureVerifier(String callbackSecret) {
        this(callbackSecret, false, 300L, (RedisSecurityStateStore) null);
    }

    public PaymentCallbackSignatureVerifier(
        String callbackSecret,
        boolean strictSignature,
        long timestampToleranceSeconds,
        RedisSecurityStateStore securityStateStore
    ) {
        this.callbackSecret = callbackSecret;
        this.strictSignature = strictSignature;
        this.timestampTolerance = Duration.ofSeconds(timestampToleranceSeconds <= 0 ? 300L : timestampToleranceSeconds);
        this.securityStateStore = securityStateStore;
    }

    public void verify(String provider, PaymentCallbackRequest request) {
        if (request == null || !StringUtils.hasText(request.signature())) {
            throw new IllegalArgumentException("missing payment callback signature");
        }

        boolean hasAmount = request.amount() != null;
        boolean hasTimestamp = StringUtils.hasText(request.timestamp());
        boolean hasNonce = StringUtils.hasText(request.nonce());
        boolean anyV2Field = hasAmount || hasTimestamp || hasNonce;
        boolean allV2Fields = hasAmount && hasTimestamp && hasNonce;

        if (!allV2Fields && (strictSignature || anyV2Field)) {
            // 强制模式下缺字段直接拒；过渡期里"半套"字段也拒，防止上游只带 amount 却不带 nonce 而绕过重放防护。
            throw new IllegalArgumentException("missing payment callback signature fields");
        }

        // 先验签：签名错就没有必要暴露时间窗/nonce 的判定细节。
        String expected = hmacSha256(callbackSecret, allV2Fields ? payloadV2(provider, request) : payloadV1(provider, request));
        if (!constantTimeEquals(expected, request.signature())) {
            throw new IllegalArgumentException("invalid payment callback signature");
        }

        if (!allV2Fields) {
            return;
        }
        verifyTimestamp(request.timestamp());
        verifyNonce(provider, request);
    }

    /** 时间窗校验：允许前后各 {@code timestampTolerance}，超出即拒。 */
    private void verifyTimestamp(String timestamp) {
        long epochMillis = parseEpochMillis(timestamp);
        long skewMillis = Math.abs(System.currentTimeMillis() - epochMillis);
        if (skewMillis > timestampTolerance.toMillis()) {
            throw new IllegalArgumentException("payment callback timestamp expired");
        }
    }

    private long parseEpochMillis(String timestamp) {
        String clean = normalize(timestamp);
        if (!clean.matches("\\d{10}|\\d{13}")) {
            throw new IllegalArgumentException("invalid payment callback timestamp");
        }
        long value = Long.parseLong(clean);
        return clean.length() == 10 ? value * 1000L : value;
    }

    /**
     * 重放防护：nonce 只允许用一次。
     *
     * <p>优先走 {@link RedisSecurityStateStore}（多实例共享）；Redis 不可用时退化到本进程内存，
     * 保证单实例下仍然拒绝重放，而不是直接放行。
     */
    private void verifyNonce(String provider, PaymentCallbackRequest request) {
        String nonceKey = normalize(provider) + ":" + normalize(request.paymentNo()) + ":" + normalize(request.nonce());
        Duration ttl = timestampTolerance.multipliedBy(2);
        Optional<Boolean> replayed = securityStateStore == null
            ? Optional.empty()
            : securityStateStore.markPaymentCallbackNonceReplay(nonceKey, ttl);
        boolean isReplay = replayed.orElseGet(() -> markLocalNonceReplay(nonceKey, ttl));
        if (isReplay) {
            throw new IllegalArgumentException("payment callback nonce replayed");
        }
    }

    private boolean markLocalNonceReplay(String nonceKey, Duration ttl) {
        long now = System.currentTimeMillis();
        localNonceExpiresAt.values().removeIf(expiresAt -> expiresAt <= now);
        if (localNonceExpiresAt.size() >= LOCAL_NONCE_CAPACITY) {
            log.warn("Local payment callback nonce cache is full, rejecting callback to stay fail-closed");
            throw new IllegalStateException("payment callback replay guard unavailable");
        }
        return localNonceExpiresAt.putIfAbsent(nonceKey, now + ttl.toMillis()) != null;
    }

    /** v2 签名串：8 字段、\n 分隔、空值以空串参与。 */
    private String payloadV2(String provider, PaymentCallbackRequest request) {
        return String.join(
            "\n",
            normalize(provider),
            normalize(request.paymentNo()),
            normalize(request.orderNo()),
            normalize(request.status()),
            normalize(request.channelTradeNo()),
            canonicalAmount(request.amount()),
            normalize(request.timestamp()),
            normalize(request.nonce())
        );
    }

    /** v1 签名串（过渡期兼容）：5 字段。 */
    private String payloadV1(String provider, PaymentCallbackRequest request) {
        return String.join(
            "\n",
            normalize(provider),
            normalize(request.paymentNo()),
            normalize(request.orderNo()),
            normalize(request.status()),
            normalize(request.channelTradeNo())
        );
    }

    /** 金额规范化：使用 toPlainString，避免 1E+2 之类科学计数法导致双方签名串不一致。 */
    public static String canonicalAmount(BigDecimal amount) {
        return amount == null ? "" : amount.toPlainString();
    }

    private String hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("payment callback signature calculation failed", ex);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
            normalize(expected).getBytes(StandardCharsets.UTF_8),
            normalize(actual).getBytes(StandardCharsets.UTF_8)
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
