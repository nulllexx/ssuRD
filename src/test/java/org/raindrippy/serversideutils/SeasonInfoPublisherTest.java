package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeasonInfoPublisherTest {
    private static final Logger LOG = Logger.getLogger("test");

    @Test
    void writesTheFieldsTheApiReads(@TempDir Path dir) throws Exception {
        File folder = dir.resolve("ServerSideUtils").toFile();
        assertTrue(SeasonInfoPublisher.publish(folder, 9, "S8 \"reboot\"", "1.21.1-R0.1-SNAPSHOT", "world", LOG));

        String raw = Files.readString(folder.toPath().resolve("season.json"), StandardCharsets.UTF_8);
        JSONObject json = (JSONObject) new JSONParser().parse(raw);
        assertEquals(9L, json.get("season"));
        assertEquals("S8 \"reboot\"", json.get("theme"));
        assertEquals("1.21.1", json.get("minecraft"));
        assertEquals("world", json.get("world"));
        assertFalse(folder.toPath().resolve("season.json.tmp").toFile().exists(), "no temp file is left behind");
    }

    @Test
    void overwritesThePreviousSeason(@TempDir Path dir) throws Exception {
        File folder = dir.toFile();
        SeasonInfoPublisher.publish(folder, 8, "Old", "1.20.1", "world", LOG);
        SeasonInfoPublisher.publish(folder, 9, "New", "1.21.1", "world", LOG);
        JSONObject json = (JSONObject) new JSONParser()
                .parse(Files.readString(folder.toPath().resolve("season.json")));
        assertEquals(9L, json.get("season"));
        assertEquals("New", json.get("theme"));
    }

    @Test
    void trimsBukkitVersions() {
        assertEquals("1.21.1", SeasonInfoPublisher.trimVersion("1.21.1-R0.1-SNAPSHOT"));
        assertEquals("1.21.1", SeasonInfoPublisher.trimVersion("1.21.1"));
        assertNull(SeasonInfoPublisher.trimVersion(null));
    }
}
