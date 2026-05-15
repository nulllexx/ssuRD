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
import java.util.UUID;

public class CredentialsManager {
    private final JavaPlugin plugin;
    private File credsFile;
    private final Map<UUID, JSONObject> playerCredentials = new HashMap<>();

    public CredentialsManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void setup() {
        credsFile = new File(Bukkit.getServer().getWorldContainer(), "authedPlayers.json");
        if (!credsFile.exists()) {
            try {
                credsFile.createNewFile();
                try (FileWriter writer = new FileWriter(credsFile)) {
                    writer.write("{}");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void load() {
        if (!credsFile.exists()) return;

        try (FileReader reader = new FileReader(credsFile)) {
            JSONParser parser = new JSONParser();
            JSONObject credentialsObj = (JSONObject) parser.parse(reader);

            for (Object key : credentialsObj.keySet()) {
                String uuidString = (String) key;
                JSONObject playerCreds = (JSONObject) credentialsObj.get(uuidString);
                UUID playerUUID = UUID.fromString(uuidString);
                playerCredentials.put(playerUUID, playerCreds);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load player credentials: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public void save() {
        try {
            JSONObject credentialsObj = new JSONObject();
            for (Map.Entry<UUID, JSONObject> entry : playerCredentials.entrySet()) {
                credentialsObj.put(entry.getKey().toString(), entry.getValue());
            }
            try (FileWriter writer = new FileWriter(credsFile)) {
                writer.write(credentialsObj.toJSONString());
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save player credentials: " + e.getMessage());
        }
    }

    public Map<UUID, JSONObject> getCredentials() {
        return playerCredentials;
    }
}
