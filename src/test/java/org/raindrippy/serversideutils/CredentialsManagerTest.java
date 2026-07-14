package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Tests for {@link CredentialsManager}. {@code setup()} resolves its file via
 * {@code Bukkit.getServer().getWorldContainer()}, so we inject a temp {@code credsFile}
 * via reflection to keep the test hermetic.
 */
class CredentialsManagerTest {

    @TempDir
    Path dir;

    private JavaPlugin plugin;
    private CredentialsManager manager;
    private File credsFile;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = mock(JavaPlugin.class);
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("CredentialsManagerTest"));
        manager = new CredentialsManager(plugin);
        credsFile = new File(dir.toFile(), "authedPlayers.json");
        injectCredsFile(credsFile);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void injectCredsFile(File file) throws Exception {
        Field f = CredentialsManager.class.getDeclaredField("credsFile");
        f.setAccessible(true);
        f.set(manager, file);
    }

    @SuppressWarnings("unchecked")
    private static JSONObject creds(String username, String password) {
        JSONObject o = new JSONObject();
        o.put("username", username);
        o.put("password", password);
        return o;
    }

    @Test
    @DisplayName("save then load round-trips player credentials")
    void saveLoadRoundTrip() {
        UUID uuid = UUID.randomUUID();
        manager.getCredentials().put(uuid, creds("Bob", "enc:cafebabe"));
        manager.save();

        CredentialsManager reloaded = new CredentialsManager(plugin);
        assertDoesNotThrow(() -> injectFieldOn(reloaded, credsFile));
        reloaded.load();

        JSONObject loaded = reloaded.getCredentials().get(uuid);
        assertEquals("Bob", loaded.get("username"));
        assertEquals("enc:cafebabe", loaded.get("password"));
    }

    @Test
    @DisplayName("load tolerates a malformed UUID key without crashing")
    void malformedUuidKeyDoesNotCrash() throws Exception {
        try (FileWriter w = new FileWriter(credsFile)) {
            // "not-a-uuid" is not parseable by UUID.fromString.
            w.write("{\"not-a-uuid\":{\"username\":\"X\",\"password\":\"y\"}}");
        }
        assertDoesNotThrow(() -> manager.load());
        // The bad key is skipped/aborted; the manager stays usable.
        assertTrue(manager.getCredentials().isEmpty());
    }

    private static void injectFieldOn(CredentialsManager target, File file) throws Exception {
        Field f = CredentialsManager.class.getDeclaredField("credsFile");
        f.setAccessible(true);
        f.set(target, file);
    }
}
