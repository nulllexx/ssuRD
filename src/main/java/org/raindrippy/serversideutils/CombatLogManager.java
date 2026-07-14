package org.raindrippy.serversideutils;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CombatLogManager {
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
                plugin.getLogger().severe("Could not create cl_strikes.yml!");
                e.printStackTrace();
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
            plugin.getLogger().severe("Could not save cl_strikes.yml!");
            e.printStackTrace();
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
}
