package org.raindrippy.serversideutils;

import org.bukkit.entity.Player;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Remembers which networks a player has already authenticated from, so a join from an unfamiliar
 * one can be pushed back through {@code /sync}.
 *
 * <p>A network is identified by its prefix — IPv4 /24, IPv6 /48 — rather than the exact address, so
 * ordinary ISP address churn does not force a re-sync. Prefixes are stored only as salted hashes
 * (see {@link CryptoService#hashNetwork(String)}); no raw address is ever written to disk or logged.
 */
public class NetworkGuard {

    /** Key under which the fingerprints live inside a player's credentials entry. */
    static final String KNOWN_NETWORKS_KEY = "knownNets";

    /** How many distinct networks a player may have remembered at once (most-recent-first). */
    static final int MAX_KNOWN_NETWORKS = 5;

    private final CryptoService cryptoService;

    public NetworkGuard(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    /**
     * Fingerprints the network the player is connected from, or returns null when the address is
     * unavailable (which callers must treat as "unrecognised", not as a pass).
     */
    public String fingerprint(Player player) {
        InetSocketAddress socketAddress = player.getAddress();
        if (socketAddress == null) return null;
        String prefix = prefixOf(socketAddress.getAddress());
        if (prefix == null) return null;
        return cryptoService.hashNetwork(prefix);
    }

    /** IPv4 -> first three octets; IPv6 -> first six bytes (/48). Null for an unusable address. */
    static String prefixOf(InetAddress address) {
        if (address == null) return null;
        byte[] bytes = address.getAddress();
        if (bytes == null) return null;

        int significantBytes = (address instanceof Inet4Address) ? 3 : 6;
        if (bytes.length < significantBytes) return null;

        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < significantBytes; i++) {
            prefix.append(String.format("%02x", bytes[i] & 0xFF));
        }
        // Tag the family so a v4 prefix can never collide with the first bytes of a v6 one.
        return (significantBytes == 3 ? "v4:" : "v6:") + prefix;
    }

    /**
     * True only when this exact fingerprint was recorded by an earlier successful {@code /sync}.
     * A credentials entry saved before this feature existed has no list and is treated as unknown,
     * so those players re-sync once rather than being grandfathered in.
     */
    public boolean isKnown(JSONObject credentials, String fingerprint) {
        if (credentials == null || fingerprint == null) return false;
        Object stored = credentials.get(KNOWN_NETWORKS_KEY);
        if (!(stored instanceof JSONArray)) return false;
        for (Object known : (JSONArray) stored) {
            if (fingerprint.equals(known)) return true;
        }
        return false;
    }

    /**
     * Records the fingerprint as most-recently-used, dropping the oldest entry once
     * {@link #MAX_KNOWN_NETWORKS} is exceeded. A null fingerprint is ignored rather than stored.
     */
    @SuppressWarnings("unchecked")
    public void remember(JSONObject credentials, String fingerprint) {
        if (credentials == null || fingerprint == null) return;

        Object stored = credentials.get(KNOWN_NETWORKS_KEY);
        JSONArray known = (stored instanceof JSONArray) ? (JSONArray) stored : new JSONArray();

        JSONArray updated = new JSONArray();
        updated.add(fingerprint);
        for (Object existing : known) {
            if (fingerprint.equals(existing)) continue;
            if (updated.size() >= MAX_KNOWN_NETWORKS) break;
            updated.add(existing);
        }
        credentials.put(KNOWN_NETWORKS_KEY, updated);
    }
}
