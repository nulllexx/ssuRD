package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.mockbukkit.mockbukkit.MockBukkit;

/** Persistence tests for {@link WarningsManager}, including the stale-key save bug. */
class WarningsManagerTest {

    @TempDir
    Path dataFolder;

    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = mock(JavaPlugin.class);
        lenient().when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("WarningsManagerTest"));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private WarningsManager freshManager() {
        WarningsManager mgr = new WarningsManager(plugin);
        mgr.setup();
        return mgr;
    }

    @Test
    @DisplayName("setup creates the warnings.yml file")
    void setupCreatesFile() {
        freshManager();
        assertTrue(new File(dataFolder.toFile(), "warnings.yml").exists());
    }

    @Test
    @DisplayName("warnings survive a save + reload")
    void persistenceRoundTrip() {
        UUID uuid = UUID.randomUUID();
        WarningsManager mgr = freshManager();
        mgr.getWarnings().put(uuid, new ArrayList<>(List.of("spamming", "griefing")));
        mgr.save();

        WarningsManager reloaded = new WarningsManager(plugin);
        reloaded.setup();
        reloaded.load();
        List<String> warnings = reloaded.getWarnings().get(uuid);
        assertEquals(List.of("spamming", "griefing"), warnings);
    }

    @Test
    @DisplayName("a UUID removed from the map is not resurrected on reload")
    void removedKeyDoesNotResurrect() {
        UUID keep = UUID.randomUUID();
        UUID drop = UUID.randomUUID();
        WarningsManager mgr = freshManager();
        mgr.getWarnings().put(keep, new ArrayList<>(List.of("keep me")));
        mgr.getWarnings().put(drop, new ArrayList<>(List.of("delete me")));
        mgr.save();

        // Remove one player's warnings entirely, then persist again.
        mgr.getWarnings().remove(drop);
        mgr.save();

        WarningsManager reloaded = new WarningsManager(plugin);
        reloaded.setup();
        reloaded.load();
        assertTrue(reloaded.getWarnings().containsKey(keep));
        assertFalse(reloaded.getWarnings().containsKey(drop),
                "a UUID removed from the map must not remain in warnings.yml after save()");
    }
}
