package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.mockbukkit.mockbukkit.MockBukkit;

/** Persistence + strike-counting tests for {@link CombatLogManager}. */
class CombatLogManagerTest {

    @TempDir
    Path dataFolder;

    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = mock(JavaPlugin.class);
        lenient().when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("CombatLogManagerTest"));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private CombatLogManager freshManager() {
        CombatLogManager mgr = new CombatLogManager(plugin);
        mgr.setup();
        return mgr;
    }

    @Test
    @DisplayName("setup creates the cl_strikes.yml file")
    void setupCreatesFile() {
        freshManager();
        assertTrue(new File(dataFolder.toFile(), "cl_strikes.yml").exists());
    }

    @Test
    @DisplayName("recordStrike increments the count and flags the player as pending")
    void recordStrikeIncrements() {
        CombatLogManager mgr = freshManager();
        UUID uuid = UUID.randomUUID();
        assertEquals(1, mgr.recordStrike(uuid));
        assertEquals(2, mgr.recordStrike(uuid));
        assertEquals(2, mgr.getStrikes(uuid));
        assertTrue(mgr.isPending(uuid));
    }

    @Test
    @DisplayName("getStrikes is 0 and isPending is false for an unknown player")
    void unknownPlayerDefaults() {
        CombatLogManager mgr = freshManager();
        UUID uuid = UUID.randomUUID();
        assertEquals(0, mgr.getStrikes(uuid));
        assertFalse(mgr.isPending(uuid));
    }

    @Test
    @DisplayName("clearPending clears the pending flag but keeps the strike count")
    void clearPending() {
        CombatLogManager mgr = freshManager();
        UUID uuid = UUID.randomUUID();
        mgr.recordStrike(uuid);
        mgr.clearPending(uuid);
        assertFalse(mgr.isPending(uuid));
        assertEquals(1, mgr.getStrikes(uuid));
    }

    @Test
    @DisplayName("strikes and pending state survive a save + reload")
    void persistenceRoundTrip() {
        UUID uuid = UUID.randomUUID();
        CombatLogManager mgr = freshManager();
        mgr.recordStrike(uuid);
        mgr.recordStrike(uuid); // count = 2, pending = true (persisted by recordStrike)

        CombatLogManager reloaded = new CombatLogManager(plugin);
        reloaded.setup();
        reloaded.load();
        assertEquals(2, reloaded.getStrikes(uuid));
        assertTrue(reloaded.isPending(uuid));
    }
}
