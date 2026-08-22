package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Tests for {@link NetworkGuard} — prefix grouping (IPv4 /24, IPv6 /48), the "unknown until proven"
 * default for entries saved before the feature existed, and the most-recent-first capped history.
 */
class NetworkGuardTest {

    private ServerMock server;
    private NetworkGuard guard;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        guard = new NetworkGuard(new CryptoService("0123456789abcdef"));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private String fingerprintOf(String ip) throws Exception {
        PlayerMock player = server.addPlayer();
        player.setAddress(new InetSocketAddress(InetAddress.getByName(ip), 25565));
        return guard.fingerprint(player);
    }

    // ---------- prefix grouping ----------

    @Test
    @DisplayName("addresses in the same IPv4 /24 share a fingerprint")
    void sameSubnetSameFingerprint() throws Exception {
        assertEquals(fingerprintOf("1.2.3.4"), fingerprintOf("1.2.3.99"));
    }

    @Test
    @DisplayName("a different IPv4 /24 gives a different fingerprint")
    void differentSubnetDifferentFingerprint() throws Exception {
        assertNotEquals(fingerprintOf("1.2.3.4"), fingerprintOf("1.2.4.4"));
    }

    @Test
    @DisplayName("IPv6 addresses are grouped by /48")
    void ipv6GroupedBy48() throws Exception {
        assertEquals(fingerprintOf("2001:db8:abcd:1::1"), fingerprintOf("2001:db8:abcd:9::5"));
        assertNotEquals(fingerprintOf("2001:db8:abcd:1::1"), fingerprintOf("2001:db8:abce:1::1"));
    }

    @Test
    @DisplayName("an unavailable address yields no fingerprint")
    void noAddressNoFingerprint() {
        PlayerMock player = server.addPlayer();
        player.setAddress(null);
        assertNull(guard.fingerprint(player));
    }

    @Test
    @DisplayName("a v4 prefix cannot collide with the leading bytes of a v6 one")
    void familiesDoNotCollide() throws Exception {
        assertNotEquals(NetworkGuard.prefixOf(InetAddress.getByName("1.2.3.4")),
                NetworkGuard.prefixOf(InetAddress.getByName("102:304::1")));
    }

    // ---------- known / remember ----------

    @Test
    @DisplayName("credentials saved before this feature existed count as an unknown network")
    void legacyEntryIsUnknown() {
        assertFalse(guard.isKnown(new JSONObject(), "some-fingerprint"));
    }

    @Test
    @DisplayName("a null fingerprint is never known and is never stored")
    void nullFingerprintRejected() {
        JSONObject creds = new JSONObject();
        assertFalse(guard.isKnown(creds, null));
        guard.remember(creds, null);
        assertFalse(creds.containsKey(NetworkGuard.KNOWN_NETWORKS_KEY));
    }

    @Test
    @DisplayName("a remembered fingerprint is recognised afterwards")
    void rememberThenKnown() {
        JSONObject creds = new JSONObject();
        guard.remember(creds, "net-a");
        assertTrue(guard.isKnown(creds, "net-a"));
        assertFalse(guard.isKnown(creds, "net-b"));
    }

    @Test
    @DisplayName("remembering an existing network does not duplicate it and moves it to the front")
    void rememberDedupes() {
        JSONObject creds = new JSONObject();
        guard.remember(creds, "net-a");
        guard.remember(creds, "net-b");
        guard.remember(creds, "net-a");

        JSONArray known = (JSONArray) creds.get(NetworkGuard.KNOWN_NETWORKS_KEY);
        assertEquals(2, known.size());
        assertEquals("net-a", known.get(0));
    }

    @Test
    @DisplayName("the history is capped, dropping the least recently used network")
    void historyIsCapped() {
        JSONObject creds = new JSONObject();
        for (int i = 0; i <= NetworkGuard.MAX_KNOWN_NETWORKS; i++) {
            guard.remember(creds, "net-" + i);
        }

        JSONArray known = (JSONArray) creds.get(NetworkGuard.KNOWN_NETWORKS_KEY);
        assertEquals(NetworkGuard.MAX_KNOWN_NETWORKS, known.size());
        assertTrue(guard.isKnown(creds, "net-" + NetworkGuard.MAX_KNOWN_NETWORKS), "newest kept");
        assertFalse(guard.isKnown(creds, "net-0"), "oldest evicted");
    }
}
