package com.xiyiyun.shop.mvp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.util.StringUtils;

final class MemberCallbackEncryption {
    static final String ALGORITHM = "AES-256-GCM";
    static final String KEY_DERIVATION = "SHA-256(xiyiyun-member-callback:v1\\n + appSecret)";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private MemberCallbackEncryption() {
    }

    static String addEncryptedData(String payloadJson, String appSecret, String eventId, String sensitiveJson) {
        if (!StringUtils.hasText(appSecret)) {
            throw new IllegalArgumentException("member API secret is required");
        }
        if (!StringUtils.hasText(eventId)) {
            throw new IllegalArgumentException("eventId is required");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            SECURE_RANDOM.nextBytes(nonce);
            byte[] ciphertext = crypt(
                Cipher.ENCRYPT_MODE,
                appSecret,
                eventId,
                nonce,
                (sensitiveJson == null ? "{}" : sensitiveJson).getBytes(StandardCharsets.UTF_8)
            );
            var root = (com.fasterxml.jackson.databind.node.ObjectNode) OBJECT_MAPPER.readTree(payloadJson);
            var encrypted = root.putObject("encryptedData");
            encrypted.put("algorithm", ALGORITHM);
            encrypted.put("keyDerivation", KEY_DERIVATION);
            encrypted.put("nonce", Base64.getEncoder().encodeToString(nonce));
            encrypted.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (GeneralSecurityException | java.io.IOException ex) {
            throw new IllegalStateException("member callback encryption failed", ex);
        }
    }

    static String decrypt(String appSecret, String eventId, String nonce, String ciphertext) {
        try {
            byte[] plaintext = crypt(
                Cipher.DECRYPT_MODE,
                appSecret,
                eventId,
                Base64.getDecoder().decode(nonce),
                Base64.getDecoder().decode(ciphertext)
            );
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("member callback decryption failed", ex);
        }
    }

    private static byte[] crypt(
        int mode,
        String appSecret,
        String eventId,
        byte[] nonce,
        byte[] input
    ) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] key = digest.digest(
            ("xiyiyun-member-callback:v1\n" + appSecret).getBytes(StandardCharsets.UTF_8)
        );
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(eventId.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(input);
    }
}
