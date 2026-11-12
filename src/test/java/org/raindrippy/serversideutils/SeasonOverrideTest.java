package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeasonOverrideTest {
    private static final Logger LOG = Logger.getLogger("test");

    private static SeasonOverride load(Path dir, String contents) throws Exception {
        if (contents != null) {
            Files.write(dir.resolve(SeasonOverride.FILE_NAME), contents.getBytes(StandardCharsets.UTF_8));
        }
        return SeasonOverride.load(dir.toFile(), 9, "S8 reboot", LOG);
    }

    @Test
    void theFileWinsOverTheDefaults(@TempDir Path dir) throws Exception {
        // Exactly what the API writes.
        SeasonOverride s = load(dir, "{\n  \"season\": 10,\n  \"theme\": \"Glacier ❄\"\n}");
        assertTrue(s.isOverridden());
        assertEquals(10, s.getSeason());
        assertEquals("Glacier ❄", s.getTheme());
    }

    @Test
    void noFileMeansTheDefaults(@TempDir Path dir) throws Exception {
        SeasonOverride s = load(dir, null);
        assertFalse(s.isOverridden());
        assertEquals(9, s.getSeason());
        assertEquals("S8 reboot", s.getTheme());
    }

    @Test
    void aBadFileFallsBackRatherThanFailing(@TempDir Path dir) throws Exception {
        for (String bad : new String[] {
                "not json", "[]", "{\"season\":\"10\",\"theme\":\"x\"}", "{\"season\":0,\"theme\":\"x\"}",
                "{\"season\":10}", "{\"season\":10,\"theme\":\"  \"}", "{\"season\":10,\"theme\":\"a\\nb\"}",
                "{\"season\":10.5,\"theme\":\"x\"}"}) {
            SeasonOverride s = load(dir, bad);
            assertFalse(s.isOverridden(), bad);
            assertEquals(9, s.getSeason(), bad);
        }
    }

    @Test
    void anUnreadableFolderFallsBack() {
        SeasonOverride s = SeasonOverride.load(new File("/nonexistent/folder"), 9, "S8 reboot", LOG);
        assertEquals(9, s.getSeason());
    }
}
