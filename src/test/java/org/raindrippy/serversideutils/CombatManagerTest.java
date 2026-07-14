package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CombatManager}. Only {@code Player.getUniqueId()} is stubbed.
 *
 * <p>Note: the true 15-second expiry cannot be tested deterministically because the class
 * reads {@link System#currentTimeMillis()} directly with no injectable clock. A future
 * refactor to inject a clock/time-supplier would unlock a proper boundary test.
 */
class CombatManagerTest {

    private CombatManager combat;
    private Player player;
    private UUID uuid;

    @BeforeEach
    void setUp() {
        combat = new CombatManager();
        uuid = UUID.randomUUID();
        player = mock(Player.class);
        lenient().when(player.getUniqueId()).thenReturn(uuid);
    }

    @Test
    @DisplayName("an untagged player is not in combat and has 0 remaining seconds")
    void untagged() {
        assertFalse(combat.isInCombat(player));
        assertEquals(0, combat.getRemainingSeconds(player));
    }

    @Test
    @DisplayName("a freshly tagged player is in combat")
    void taggedIsInCombat() {
        combat.tagPlayer(uuid);
        assertTrue(combat.isInCombat(player));
    }

    @Test
    @DisplayName("remaining seconds after tagging is in (0, 15]")
    void remainingSecondsAfterTag() {
        combat.tagPlayer(uuid);
        long remaining = combat.getRemainingSeconds(player);
        // Integer division truncates 14_999ms -> 14s, so we assert the achievable window.
        assertTrue(remaining > 0 && remaining <= 15,
                "expected remaining in (0,15] but was " + remaining);
    }

    @Test
    @DisplayName("clearCombat removes the tag")
    void clearCombat() {
        combat.tagPlayer(uuid);
        assertTrue(combat.isInCombat(player));
        combat.clearCombat(player);
        assertFalse(combat.isInCombat(player));
        assertEquals(0, combat.getRemainingSeconds(player));
    }

    @Test
    @DisplayName("tagging one player does not put another in combat")
    void isolationBetweenPlayers() {
        combat.tagPlayer(uuid);
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        assertFalse(combat.isInCombat(other));
    }
}
