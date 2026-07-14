package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.mockbukkit.mockbukkit.MockBukkit;

/** Config-file load/save tests for {@link ConfigManager}, including the key-clobbering save bug. */
class ConfigManagerTest {

    @TempDir
    Path dataFolder;

    private JavaPlugin plugin;
    private ConfigManager config;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = mock(JavaPlugin.class);
        lenient().when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("ConfigManagerTest"));
        config = new ConfigManager(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private File confFile() {
        return new File(dataFolder.toFile(), "conf.json");
    }

    @SuppressWarnings("unchecked")
    private void writeConf(JSONObject obj) throws Exception {
        try (FileWriter w = new FileWriter(confFile())) {
            w.write(obj.toJSONString());
        }
    }

    private JSONObject readConf() throws Exception {
        try (FileReader r = new FileReader(confFile())) {
            return (JSONObject) new JSONParser().parse(r);
        }
    }

    @Test
    @DisplayName("loadSeed on a missing config returns 0 and writes a default file")
    void loadSeedMissingFile() {
        assertEquals(0L, config.loadSeed());
        assertTrue(confFile().exists());
    }

    @Test
    @DisplayName("loadSeed returns the stored numeric seed")
    @SuppressWarnings("unchecked")
    void loadSeedValid() throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("seed", 123456789L);
        writeConf(obj);
        assertEquals(123456789L, config.loadSeed());
    }

    @Test
    @DisplayName("loadAll reads tracking and lsrev flags from config")
    @SuppressWarnings("unchecked")
    void loadAllReadsFlags() throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("tracking", false);
        obj.put("lsrev", true);
        writeConf(obj);

        config.loadAll();
        assertFalse(config.isTrackingEnabled());
        assertTrue(config.isLifestealReviveEnabled());
    }

    @Test
    @DisplayName("loadAll defaults tracking=true / lsrev=false when the config is missing")
    void loadAllDefaults() {
        config.loadAll();
        assertTrue(config.isTrackingEnabled());
        assertFalse(config.isLifestealReviveEnabled());
    }

    @Test
    @DisplayName("saveTracking preserves the seed and lsrev keys in conf.json")
    @SuppressWarnings("unchecked")
    void saveTrackingPreservesSiblingKeys() throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("seed", 999L);
        obj.put("tracking", true);
        obj.put("lsrev", true);
        writeConf(obj);

        config.saveTracking(false);

        JSONObject after = readConf();
        assertEquals(false, after.get("tracking"));
        // Correct behavior: unrelated settings must be preserved when toggling tracking.
        assertTrue(after.containsKey("seed"), "saveTracking must not drop the 'seed' key");
        assertTrue(after.containsKey("lsrev"), "saveTracking must not drop the 'lsrev' key");
    }
}
