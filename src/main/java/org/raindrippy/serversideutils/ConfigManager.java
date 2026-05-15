package org.raindrippy.serversideutils;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ConfigManager {
    private final JavaPlugin plugin;
    private final File playerCountFile;
    private File confFile;
    private final Map<String, Object> lifesteal = new HashMap<>();

    private boolean trackingEnabled = true;
    private boolean lifestealReviveEnabled = false;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.playerCountFile = new File(System.getProperty("user.dir"), "plrCount.json");
    }

    public boolean isTrackingEnabled() {
        return trackingEnabled;
    }

    public boolean isLifestealReviveEnabled() {
        return lifestealReviveEnabled;
    }

    public void loadAll() {
        this.trackingEnabled = loadTracking();
        this.lifestealReviveEnabled = loadLsRev();
    }

    @SuppressWarnings("unchecked")
    public void savePlayerCount() {
        int playerCount = Bukkit.getOnlinePlayers().size();
        JSONObject data = new JSONObject();
        data.put("players", playerCount);
        try {
            if (!playerCountFile.exists()) {
                playerCountFile.createNewFile();
            }
            try (FileWriter writer = new FileWriter(playerCountFile)) {
                writer.write(data.toJSONString());
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save player count: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public long loadSeed() {
        confFile = new File(plugin.getDataFolder(), "conf.json");

        if (!confFile.exists()) {
            plugin.getLogger().warning("Config file not found. Creating new config with seed = 0.");
            JSONObject defaultConfig = new JSONObject();
            defaultConfig.put("seed", 0L);

            try (FileWriter writer = new FileWriter(confFile)) {
                writer.write(defaultConfig.toJSONString());
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to write default config: " + e.getMessage());
            }

            return 0L;
        }

        try (FileReader reader = new FileReader(confFile)) {
            JSONParser parser = new JSONParser();
            JSONObject jsonObject = (JSONObject) parser.parse(reader);

            Object seedObj = jsonObject.get("seed");
            if (seedObj instanceof Number) {
                return ((Number) seedObj).longValue();
            } else {
                plugin.getLogger().warning("Invalid or missing seed value in config. Defaulting to 0.");
                jsonObject.put("seed", 0L);
                try (FileWriter writer = new FileWriter(confFile)) {
                    writer.write(jsonObject.toJSONString());
                } catch (IOException e) {
                    plugin.getLogger().severe("Failed to update config: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load configuration: " + e.getMessage());
        }
        return 0L;
    }

    @SuppressWarnings("unchecked")
    private boolean loadLsRev() {
        confFile = new File(plugin.getDataFolder(), "conf.json");

        if (!confFile.exists()) {
            plugin.getLogger().warning("Config file not found. Creating new config with lsrev = false.");
            JSONObject defaultConfig = new JSONObject();
            defaultConfig.put("lsrev", false);

            try (FileWriter writer = new FileWriter(confFile)) {
                writer.write(defaultConfig.toJSONString());
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to write default config: " + e.getMessage());
            }

            return false;
        }

        try (FileReader reader = new FileReader(confFile)) {
            JSONParser parser = new JSONParser();
            JSONObject jsonObject = (JSONObject) parser.parse(reader);

            Object lsrevObj = jsonObject.get("lsrev");
            if (lsrevObj instanceof Boolean) {
                return (Boolean) lsrevObj;
            } else {
                plugin.getLogger().warning("Invalid or missing LifeSteal revive value in config. Defaulting to false.");
                jsonObject.put("lsrev", false);
                try (FileWriter writer = new FileWriter(confFile)) {
                    writer.write(jsonObject.toJSONString());
                } catch (IOException e) {
                    plugin.getLogger().severe("Failed to update config: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load configuration: " + e.getMessage());
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean loadTracking() {
        confFile = new File(plugin.getDataFolder(), "conf.json");

        if (!confFile.exists()) {
            plugin.getLogger().warning("Config file not found. Creating new config with tracking = true.");
            JSONObject defaultConfig = new JSONObject();
            defaultConfig.put("tracking", true);

            try (FileWriter writer = new FileWriter(confFile)) {
                writer.write(defaultConfig.toJSONString());
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to write default config: " + e.getMessage());
            }

            return true;
        }
        try (FileReader reader = new FileReader(confFile)) {
            JSONParser parser = new JSONParser();
            JSONObject jsonObject = (JSONObject) parser.parse(reader);

            Object trackingObj = jsonObject.get("tracking");
            if (trackingObj instanceof Boolean) {
                return (Boolean) trackingObj;
            } else {
                plugin.getLogger().warning("Invalid tracking value in config. Defaulting to true.");
                jsonObject.put("tracking", true);
                try (FileWriter writer = new FileWriter(confFile)) {
                    writer.write(jsonObject.toJSONString());
                } catch (IOException e) {
                    plugin.getLogger().severe("Failed to update config: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load configuration: " + e.getMessage());
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public void saveTracking(boolean value) {
        this.trackingEnabled = value;
        confFile = new File(plugin.getDataFolder(), "conf.json");
        if (!confFile.exists()) {
            confFile.getParentFile().mkdirs();
            plugin.saveResource("conf.json", false);
        }
        JSONObject trackState = new JSONObject();
        trackState.put("tracking", value);
        try {
            FileWriter dataWriter = new FileWriter(confFile);
            dataWriter.write(trackState.toJSONString());
            dataWriter.close();
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save configuration: " + e.getMessage());
        }
    }

    public void saveLsRev(boolean value) {
        this.lifestealReviveEnabled = value;
        lifesteal.put("lsrev", value);
        saveLifestealDict();
    }

    public void saveSeed(long seed) {
        lifesteal.put("seed", seed);
        saveLifestealDict();
    }

    @SuppressWarnings("unchecked")
    private void saveLifestealDict() {
        if (confFile == null) {
            confFile = new File(plugin.getDataFolder(), "conf.json");
        }

        if (!confFile.exists()) {
            confFile.getParentFile().mkdirs();
            plugin.saveResource("conf.json", false);
        }

        JSONObject trackState = new JSONObject();
        trackState.putAll(lifesteal);

        try (FileWriter dataWriter = new FileWriter(confFile)) {
            dataWriter.write(trackState.toJSONString());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save configuration: " + e.getMessage());
        }
    }
}
