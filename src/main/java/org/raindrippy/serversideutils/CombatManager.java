package org.raindrippy.serversideutils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;

public class CombatManager {
    private final Map<UUID, Long> combatTagged = new HashMap<>();
    private static final long COMBAT_DURATION_MS = 15_000; // this is 15 seconds
    public void tagPlayer(UUID playerUUID) {
        combatTagged.put(playerUUID, System.currentTimeMillis());
    }
    // bool for if player is fighting (pvp)
    public boolean isInCombat(Player player) {
        Long taggedAt = combatTagged.get(player.getUniqueId());
        if (taggedAt == null) return false;
        return (System.currentTimeMillis() - taggedAt) < COMBAT_DURATION_MS; // cond for if the player is still in combat
    }
    public void clearCombat(Player player) {
        combatTagged.remove(player.getUniqueId());
    }
    public long getRemainingSeconds(Player player) {
        Long taggedAt = combatTagged.get(player.getUniqueId());
        if (taggedAt == null) return 0;
        long remaining = COMBAT_DURATION_MS - (System.currentTimeMillis() - taggedAt);
        return Math.max(0, remaining / 1000); // return remaining seconds non negative
    }
}
