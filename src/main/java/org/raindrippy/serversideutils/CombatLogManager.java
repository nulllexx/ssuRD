package org.raindrippy.serversideutils;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CombatLogManager {
    /** Matches the warning filed for a strike; must stay in step with {@link #warningText(int)}. */
    private static final Pattern STRIKE_WARNING = Pattern.compile("^Combat logging \\(strike #(\\d+)\\)$");

    private final JavaPlugin plugin;
    private File strikesFile;
    private FileConfiguration strikesConfig;
    private final Map<UUID, Integer> strikes = new HashMap<>();
    private final Set<UUID> pendingNotify = new HashSet<>();

    public CombatLogManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void setup() {
        strikesFile = new File(plugin.getDataFolder(), "cl_strikes.yml");
        if (!strikesFile.exists()) {
            strikesFile.getParentFile().mkdirs();
            try {
                strikesFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create cl_strikes.yml!", e);
            }
        }
        strikesConfig = YamlConfiguration.loadConfiguration(strikesFile);
    }

    public void load() {
        for (String uuidString : strikesConfig.getKeys(false)) {
            UUID uuid = UUID.fromString(uuidString);
            strikes.put(uuid, strikesConfig.getInt(uuidString + ".strikes"));
            if (strikesConfig.getBoolean(uuidString + ".pending")) {
                pendingNotify.add(uuid);
            }
        }
    }

    public void save() {
        // Drop entries removed from the map so stale strikes don't resurrect on reload.
        for (String key : new HashSet<>(strikesConfig.getKeys(false))) {
            strikesConfig.set(key, null);
        }
        for (UUID uuid : strikes.keySet()) {
            String key = uuid.toString();
            strikesConfig.set(key + ".strikes", strikes.get(uuid));
            strikesConfig.set(key + ".pending", pendingNotify.contains(uuid));
        }
        try {
            strikesConfig.save(strikesFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save cl_strikes.yml!", e);
        }
    }

    public int recordStrike(UUID uuid) {
        int count = strikes.getOrDefault(uuid, 0) + 1;
        strikes.put(uuid, count);
        pendingNotify.add(uuid);
        save();
        return count;
    }

    public int getStrikes(UUID uuid) {
        return strikes.getOrDefault(uuid, 0);
    }

    public boolean isPending(UUID uuid) {
        return pendingNotify.contains(uuid);
    }

    public void clearPending(UUID uuid) {
        pendingNotify.remove(uuid);
        save();
    }

    /**
     * Removes one strike. Strikes are just a count, so removing #k means the ones after it move
     * down a place; {@link #removeStrikeWarning} renumbers their warnings to match.
     *
     * @return false if the player has no such strike
     */
    public boolean removeStrike(UUID uuid, int strike) {
        int count = getStrikes(uuid);
        if (strike < 1 || strike > count) return false;
        // The pending join notice is about the latest strike. If that is the one being struck
        // off, the player shouldn't log in to a message about it.
        if (strike == count) pendingNotify.remove(uuid);
        setCount(uuid, count - 1);
        save();
        return true;
    }

    /** Removes every strike the player has. Returns how many there were. */
    public int clearStrikes(UUID uuid) {
        Integer removed = strikes.remove(uuid);
        pendingNotify.remove(uuid);
        save();
        return removed == null ? 0 : removed;
    }

    private void setCount(UUID uuid, int count) {
        if (count <= 0) {
            strikes.remove(uuid);
            pendingNotify.remove(uuid);
        } else {
            strikes.put(uuid, count);
        }
    }

    // ---- Warnings filed for strikes ---------------------------------------------------------

    /** The warning text filed for a strike. The listener uses this so the format lives in one place. */
    public static String warningText(int strike) {
        return "Combat logging (strike #" + strike + ")";
    }

    /** The strike number a warning was filed for, or -1 if it isn't a combat-log warning. */
    static int strikeNumberOf(String warning) {
        if (warning == null) return -1;
        Matcher m = STRIKE_WARNING.matcher(warning);
        if (!m.matches()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Removes the warning for {@code strike} and renumbers the warnings for later strikes down by
     * one, keeping them in step with {@link #removeStrike}. Other warnings are left alone.
     *
     * @return how many warnings were removed
     */
    static int removeStrikeWarning(List<String> warnings, int strike) {
        int removed = 0;
        for (ListIterator<String> it = warnings.listIterator(); it.hasNext(); ) {
            int n = strikeNumberOf(it.next());
            if (n == strike) {
                it.remove();
                removed++;
            } else if (n > strike) {
                it.set(warningText(n - 1));
            }
        }
        return removed;
    }

    /** Removes every combat-log warning. Returns how many were removed. */
    static int removeAllStrikeWarnings(List<String> warnings) {
        int before = warnings.size();
        warnings.removeIf(w -> strikeNumberOf(w) > 0);
        return before - warnings.size();
    }
}