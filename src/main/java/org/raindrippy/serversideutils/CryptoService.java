package org.raindrippy.serversideutils;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class CryptoService {
    private final SecretKeySpec key;

    public CryptoService(String aesKey) {
        if (aesKey == null || aesKey.isBlank()) {
            // An unset/blank AES_KEY is a misconfiguration: fail loudly at startup rather than
            // silently deriving a key from empty material.
            throw new IllegalArgumentException("AES key must not be null or blank");
        }
        // Derive a fixed-length AES-256 key by hashing the configured key material. This accepts
        // key strings of any length (e.g. 16-, 32- or 64-character keys) instead of requiring the
        // raw UTF-8 bytes to already be exactly 16/24/32 bytes long.
        this.key = deriveKey(aesKey);
    }

    private static SecretKeySpec deriveKey(String aesKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(aesKey.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS to be present on every JVM; this is unreachable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public String encrypt(String password) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            // Never fall back to returning the plaintext password: a misconfigured key must
            // fail loudly rather than silently persisting credentials in the clear.
            throw new IllegalStateException("Password encryption failed", e);
        }
    }

    public String decrypt(String encryptedPassword) {
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] decoded = Base64.getDecoder().decode(encryptedPassword);
            byte[] decrypted = cipher.doFinal(decoded);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Password decryption failed", e);
        }
    }
}
