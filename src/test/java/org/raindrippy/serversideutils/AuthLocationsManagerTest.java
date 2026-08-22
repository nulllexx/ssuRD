package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Persistence tests for {@link AuthLocationsManager}, the store that lets a player parked in the
 * auth world be put back exactly where they came from.
 */
class AuthLocationsManagerTest {

    @TempDir
    Path dataFolder;

    private ServerMock server;
    private JavaPlugin plugin;
    private WorldMock overworld;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        overworld = server.addSimpleWorld("world");
        plugin = mock(JavaPlugin.class);
        lenient().when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("AuthLocationsManagerTest"));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private AuthLocationsManager freshManager() {
        AuthLocationsManager mgr = new AuthLocationsManager(plugin);
        mgr.setup();
        return mgr;
    }

    private AuthLocationsManager reload() {
        AuthLocationsManager mgr = new AuthLocationsManager(plugin);
        mgr.setup();
        mgr.load();
        return mgr;
    }

    @Test
    @DisplayName("a recorded origin round-trips through disk")
    void recordSurvivesReload() {
        UUID uuid = UUID.randomUUID();
        Location origin = new Location(overworld, 123.5, 64.0, -77.25, 90.0f, 12.0f);
        freshManager().recordIfAbsent(uuid, origin, GameMode.SURVIVAL);

        Location restored = reload().getOrigin(uuid);
        assertEquals("world", restored.getWorld().getName());
        assertEquals(123.5, restored.getX());
        assertEquals(64.0, restored.getY());
        assertEquals(-77.25, restored.getZ());
        assertEquals(90.0f, restored.getYaw());
        assertEquals(12.0f, restored.getPitch());
    }

    @Test
    @DisplayName("a second record does not overwrite the first (never strand a player)")
    void recordIfAbsentDoesNotOverwrite() {
        UUID uuid = UUID.randomUUID();
        AuthLocationsManager mgr = freshManager();
        mgr.recordIfAbsent(uuid, new Location(overworld, 100, 64, 100), GameMode.SURVIVAL);
        // A re-freeze passes the auth world's spawn and the holding gamemode; the real ones must win.
        WorldMock authWorld = server.addSimpleWorld("authhub");
        mgr.recordIfAbsent(uuid, new Location(authWorld, 0, 64, 0), GameMode.ADVENTURE);

        assertEquals("world", mgr.getOrigin(uuid).getWorld().getName());
        assertEquals(100, mgr.getOrigin(uuid).getX());
        assertEquals(GameMode.SURVIVAL, mgr.getGameMode(uuid));
    }

    @Test
    @DisplayName("clearing an origin removes it from disk too")
    void clearRemovesEntry() {
        UUID uuid = UUID.randomUUID();
        AuthLocationsManager mgr = freshManager();
        mgr.recordIfAbsent(uuid, new Location(overworld, 1, 2, 3), GameMode.SURVIVAL);
        mgr.clearOrigin(uuid);

        assertFalse(mgr.hasOrigin(uuid));
        assertFalse(reload().hasOrigin(uuid));
    }

    @Test
    @DisplayName("an origin whose world was deleted still counts as recorded, but resolves to null")
    void deletedWorldStillCountsAsRecorded() {
        UUID uuid = UUID.randomUUID();
        freshManager().recordIfAbsent(uuid, new Location(overworld, 10, 64, 10), GameMode.SURVIVAL);

        // Simulate a season reset dropping the world the origin referred to.
        server.removeWorld(overworld);
        AuthLocationsManager reloaded = reload();

        assertTrue(reloaded.hasOrigin(uuid), "the entry must survive so the player can be rescued");
        assertNull(reloaded.getOrigin(uuid), "but it must not resolve to a location in a dead world");
    }

    @Test
    @DisplayName("the pre-freeze gamemode round-trips through disk")
    void gameModeSurvivesReload() {
        UUID uuid = UUID.randomUUID();
        freshManager().recordIfAbsent(uuid, new Location(overworld, 1, 2, 3), GameMode.CREATIVE);
        assertEquals(GameMode.CREATIVE, reload().getGameMode(uuid));
    }

    @Test
    @DisplayName("an entry saved without a gamemode loads with a null one rather than failing")
    void missingGameModeIsTolerated() {
        UUID uuid = UUID.randomUUID();
        freshManager().recordIfAbsent(uuid, new Location(overworld, 1, 2, 3), null);

        AuthLocationsManager reloaded = reload();
        assertTrue(reloaded.hasOrigin(uuid));
        assertNull(reloaded.getGameMode(uuid));
    }

    @Test
    @DisplayName("an unknown player has no origin")
    void unknownPlayerHasNoOrigin() {
        AuthLocationsManager mgr = freshManager();
        assertFalse(mgr.hasOrigin(UUID.randomUUID()));
        assertNull(mgr.getOrigin(UUID.randomUUID()));
    }

    @Test
    @DisplayName("an awkward world name round-trips")
    void worldNameWithSeparator() {
        WorldMock odd = server.addSimpleWorld("we;rd");
        UUID uuid = UUID.randomUUID();
        freshManager().recordIfAbsent(uuid, new Location(odd, 5, 6, 7), GameMode.SURVIVAL);

        Location restored = reload().getOrigin(uuid);
        assertEquals("we;rd", restored.getWorld().getName());
        assertEquals(5, restored.getX());
    }
}
