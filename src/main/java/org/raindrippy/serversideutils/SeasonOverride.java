package org.raindrippy.serversideutils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.logging.Logger;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * The season number and theme in effect, read on startup from {@code season-override.json} in the
 * plugin's data folder:
 *
 * <pre>{"season": 10, "theme": "Glacier"}</pre>
 *
 * <p>The admin panel's "End season" writes this file for the season that follows, so a new season
 * needs no rebuild of the plugin. Without the file, or if it is unreadable or invalid, the
 * built-in defaults are used and the reason is logged; a bad file never stops the plugin.
 *
 * <p>Field names are shared with the RainDrippy API ({@code season/reset.rs}); change both
 * together.
 */
public final class SeasonOverride {
    static final String FILE_NAME = "season-override.json";
    static final int MAX_THEME = 64;

    private final int season;
    private final String theme;
    private final boolean overridden;

    private SeasonOverride(int season, String theme, boolean overridden) {
        this.season = season;
        this.theme = theme;
        this.overridden = overridden;
    }

    public int getSeason() {
        return season;
    }

    public String getTheme() {
        return theme;
    }

    /** Whether the values came from the file rather than the defaults. */
    public boolean isOverridden() {
        return overridden;
    }

    public static SeasonOverride load(File dataFolder, int defaultSeason, String defaultTheme, Logger logger) {
        SeasonOverride fallback = new SeasonOverride(defaultSeason, defaultTheme, false);
        File file = new File(dataFolder, FILE_NAME);
        String raw;
        try {
            raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return fallback;
        } catch (IOException e) {
            logger.warning("Could not read " + FILE_NAME + " (" + e.getMessage() + "); using season "
                    + defaultSeason + " \"" + defaultTheme + "\"");
            return fallback;
        }

        Object parsed;
        try {
            parsed = new JSONParser().parse(raw);
        } catch (ParseException e) {
            logger.severe(FILE_NAME + " is not valid JSON; using season " + defaultSeason + " \""
                    + defaultTheme + "\"");
            return fallback;
        }
        if (!(parsed instanceof JSONObject)) {
            logger.severe(FILE_NAME + " must be a JSON object; using the built-in season");
            return fallback;
        }
        JSONObject json = (JSONObject) parsed;
        Object seasonValue = json.get("season");
        Object themeValue = json.get("theme");
        if (!(seasonValue instanceof Long) || (Long) seasonValue < 1 || (Long) seasonValue > 100_000) {
            logger.severe(FILE_NAME + ": \"season\" must be a whole number from 1 to 100000; using the "
                    + "built-in season");
            return fallback;
        }
        if (!(themeValue instanceof String)) {
            logger.severe(FILE_NAME + ": \"theme\" must be text; using the built-in season");
            return fallback;
        }
        String theme = ((String) themeValue).trim();
        if (theme.isEmpty() || theme.codePointCount(0, theme.length()) > MAX_THEME
                || theme.chars().anyMatch(Character::isISOControl)) {
            logger.severe(FILE_NAME + ": \"theme\" must be 1 to " + MAX_THEME
                    + " characters on one line; using the built-in season");
            return fallback;
        }
        int season = ((Long) seasonValue).intValue();
        logger.info("Season " + season + " \"" + theme + "\" (from " + FILE_NAME + ")");
        return new SeasonOverride(season, theme, true);
    }
}
