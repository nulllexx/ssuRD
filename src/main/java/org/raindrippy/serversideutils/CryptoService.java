package org.raindrippy.serversideutils;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public class CryptoService {
    private final String aesKey;

    public CryptoService(String aesKey) {
        this.aesKey = aesKey;
    }

    public String encrypt(String password) {
        try {
            SecretKeySpec key = new SecretKeySpec(aesKey.getBytes(StandardCharsets.UTF_8), "AES");
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

    /**
     * Returns a stable, non-reversible fingerprint for a network prefix, salted with the AES key so
     * the stored value is useless outside this server. Used to remember which networks a player has
     * already authenticated from; raw addresses are never persisted or logged.
     */
    public String hashNetwork(String prefix) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(aesKey.getBytes(StandardCharsets.UTF_8));
            byte[] hashed = digest.digest(prefix.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (Exception e) {
            // Same fail-loud contract as encrypt: never fall back to storing the prefix in the clear.
            throw new IllegalStateException("Network fingerprinting failed", e);
        }
    }

    public String decrypt(String encryptedPassword) {
        try {
            SecretKeySpec key = new SecretKeySpec(aesKey.getBytes(StandardCharsets.UTF_8), "AES");
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
