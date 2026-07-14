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
    @DisplayName("encrypt with an invalid-length key throws instead of leaking plaintext")
    void invalidKeyThrows() {
        // A 5-byte key is not a legal AES key length; encryption must fail loudly rather than
        // silently returning the raw password (which would persist credentials in the clear).
        CryptoService crypto = new CryptoService("short");
        assertThrows(RuntimeException.class, () -> crypto.encrypt("topsecret"));
    }
}
