package com.xiyiyun.shop.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CardCipherService {
    private static final String KEY_VERSION = "v1";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec key;

    public CardCipherService(@Value("${xiyiyun.card.encryption-secret:xiyiyun_dev_card_secret}") String secret) {
        this.key = new SecretKeySpec(sha256(secret == null ? "" : secret), "AES");
    }

    public EncryptedCard encrypt(String content) {
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, content.getBytes(StandardCharsets.UTF_8), nonce);
        return new EncryptedCard(ciphertext, nonce, KEY_VERSION, sha256Hex(content));
    }

    public String decrypt(byte[] ciphertext, byte[] nonce) {
        byte[] plaintext = crypt(Cipher.DECRYPT_MODE, ciphertext, nonce);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    public String hash(String content) {
        return sha256Hex(content);
    }

    private byte[] crypt(int mode, byte[] input, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("card cipher operation failed", ex);
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("sha256 unavailable", ex);
        }
    }

    private String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    public record EncryptedCard(byte[] ciphertext, byte[] nonce, String keyVersion, String hash) {}
}
