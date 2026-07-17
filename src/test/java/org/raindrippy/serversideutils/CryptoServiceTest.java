package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link CryptoService} AES round-trip and the (dangerous) silent fallback. */
class CryptoServiceTest {

    // Exactly 16 bytes -> valid AES-128 key.
    private static final String VALID_KEY = "0123456789abcdef";

    @Test
    @DisplayName("encrypt then decrypt returns the original plaintext")
    void roundTrip() {
        CryptoService crypto = new CryptoService(VALID_KEY);
        String secret = "hunter2-p@ssw0rd";
        String cipher = crypto.encrypt(secret);
        assertNotEquals(secret, cipher, "ciphertext should differ from plaintext");
        assertEquals(secret, crypto.decrypt(cipher));
    }

    @Test
    @DisplayName("AES/ECB is deterministic: same input -> same ciphertext")
    void deterministic() {
        CryptoService crypto = new CryptoService(VALID_KEY);
        assertEquals(crypto.encrypt("abc"), crypto.encrypt("abc"));
    }

    @Test
    @DisplayName("empty string round-trips")
    void emptyRoundTrip() {
        CryptoService crypto = new CryptoService(VALID_KEY);
        assertEquals("", crypto.decrypt(crypto.encrypt("")));
    }

    @Test
    @DisplayName("a blank key is a misconfiguration and fails loudly at construction")
    void blankKeyThrows() {
        // An unset/blank AES_KEY must fail loudly rather than silently deriving a key from empty
        // material and persisting credentials under a predictable key.
        assertThrows(RuntimeException.class, () -> new CryptoService(""));
        assertThrows(RuntimeException.class, () -> new CryptoService(null));
    }

    @Test
    @DisplayName("keys of any non-blank length are accepted (regression for 64-byte key)")
    void arbitraryLengthKeyRoundTrips() {
        // Regression: a 64-character key previously threw "Invalid AES key length: 64 bytes".
        // The key material is now hashed to a fixed-length AES-256 key, so any length works.
        String longKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        CryptoService crypto = new CryptoService(longKey);
        assertEquals("topsecret", crypto.decrypt(crypto.encrypt("topsecret")));
    }
}
