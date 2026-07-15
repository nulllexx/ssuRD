package org.raindrippy.serversideutils;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class WarningsManager {
    private final JavaPlugin plugin;
    private File warningsFile;
    private FileConfiguration warningsConfig;
    private final Map<UUID, List<String>> playerWarnings = new HashMap<>();

    public WarningsManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void setup() {
        warningsFile = new File(plugin.getDataFolder(), "warnings.yml");
        if (!warningsFile.exists()) {
            warningsFile.getParentFile().mkdirs();
            try {
                warningsFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create warnings.yml!", e);
            }
        }
        warningsConfig = YamlConfiguration.loadConfiguration(warningsFile);
    }

    public void load() {
        for (String uuidString : warningsConfig.getKeys(false)) {
            UUID uuid = UUID.fromString(uuidString);
            List<String> warnings = warningsConfig.getStringList(uuidString);
            playerWarnings.put(uuid, new ArrayList<>(warnings));
        }
    }

    public void save() {
        // Drop entries removed from the map so cleared warnings don't resurrect on reload.
        for (String key : new HashSet<>(warningsConfig.getKeys(false))) {
            warningsConfig.set(key, null);
        }
        for (UUID uuid : playerWarnings.keySet()) {
            warningsConfig.set(uuid.toString(), playerWarnings.get(uuid));
        }
        try {
            warningsConfig.save(warningsFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save warnings.yml!", e);
        }
    }

    public Map<UUID, List<String>> getWarnings() {
        return playerWarnings;
    }
}
