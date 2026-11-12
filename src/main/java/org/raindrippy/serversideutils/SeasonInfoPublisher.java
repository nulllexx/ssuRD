package org.raindrippy.serversideutils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.simple.JSONObject;

/**
 * Publishes the current season's details as {@code season.json} in the plugin's data folder, where
 * the RainDrippy API reads them for the admin panel's "End season" action.
 *
 * <p>The season number and theme live here, in the plugin, because the plugin is what shows them
 * to players (the scoreboard). Publishing them rather than duplicating them in the API means the
 * archive always records what players actually saw.
 *
 * <p>Written to a temporary file and moved into place, so a reader never sees half of it.
 */
public final class SeasonInfoPublisher {
    static final String FILE_NAME = "season.json";

    private SeasonInfoPublisher() {
    }

    /**
     * @param minecraftVersion e.g. {@code 1.21.1}; a Bukkit version such as {@code 1.21.1-R0.1-SNAPSHOT}
     *                         is trimmed to that
     * @param mainWorld        the name of the server's main world folder, e.g. {@code world}
     * @return whether the file was written
     */
    @SuppressWarnings("unchecked")
    public static boolean publish(File dataFolder, int season, String theme, String minecraftVersion,
            String mainWorld, Logger logger) {
        JSONObject info = new JSONObject();
        info.put("season", season);
        info.put("theme", theme);
        info.put("minecraft", trimVersion(minecraftVersion));
        info.put("world", mainWorld);

        try {
            Files.createDirectories(dataFolder.toPath());
            File target = new File(dataFolder, FILE_NAME);
            File tmp = new File(dataFolder, FILE_NAME + ".tmp");
            Files.write(tmp.toPath(), info.toJSONString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not publish season info for the panel", e);
            return false;
        }
    }

    /** {@code 1.21.1-R0.1-SNAPSHOT} to {@code 1.21.1}; anything else as given. */
    static String trimVersion(String version) {
        if (version == null) {
            return null;
        }
        int dash = version.indexOf('-');
        return dash > 0 ? version.substring(0, dash) : version;
    }
}
