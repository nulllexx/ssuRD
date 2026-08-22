package org.raindrippy.serversideutils;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Remembers where a player was standing, and which gamemode they were in, before being parked in
 * the auth world — so a successful {@code /sync} can put them back exactly as they were.
 *
 * <p>This has to survive a restart: a player who is parked, quits without syncing, and comes back
 * after a restart would otherwise lose their real position for good, and would be returned to the
 * holding gamemode rather than the one they were actually playing in.
 */
public class AuthLocationsManager {

    /** One player's pre-freeze state. The world is kept by name; it may no longer exist. */
    private static final class Origin {
        private final String worldName;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final float pitch;
        private final GameMode gameMode;

        private Origin(String worldName, double x, double y, double z, float yaw, float pitch,
                       GameMode gameMode) {
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.gameMode = gameMode;
        }
    }

    private final JavaPlugin plugin;
    private File locationsFile;
    private FileConfiguration locationsConfig;
    private final Map<UUID, Origin> origins = new HashMap<>();

    public AuthLocationsManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void setup() {
        locationsFile = new File(plugin.getDataFolder(), "auth_origins.yml");
        if (!locationsFile.exists()) {
            locationsFile.getParentFile().mkdirs();
            try {
                locationsFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create auth_origins.yml!", e);
            }
        }
        locationsConfig = YamlConfiguration.loadConfiguration(locationsFile);
    }

    public void load() {
        origins.clear();
        for (String uuidString : locationsConfig.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidString);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping malformed UUID key in auth_origins.yml: " + uuidString);
                continue;
            }
            ConfigurationSection section = locationsConfig.getConfigurationSection(uuidString);
            // The world is stored by name and deliberately not resolved here: a world dropped by a
            // season reset must still count as a recorded origin so the player is rescued from the
            // auth world rather than silently left in it.
            String worldName = (section == null) ? null : section.getString("world");
            if (worldName == null) {
                plugin.getLogger().warning("Skipping malformed auth origin for " + uuidString);
                continue;
            }
            origins.put(uuid, new Origin(
                    worldName,
                    section.getDouble("x"),
                    section.getDouble("y"),
                    section.getDouble("z"),
                    (float) section.getDouble("yaw"),
                    (float) section.getDouble("pitch"),
                    parseGameMode(section.getString("gamemode"))));
        }
    }

    public void save() {
        // Drop keys removed from the map so a restored origin does not resurrect on reload.
        for (String key : new HashSet<>(locationsConfig.getKeys(false))) {
            locationsConfig.set(key, null);
        }
        for (Map.Entry<UUID, Origin> entry : origins.entrySet()) {
            String base = entry.getKey().toString();
            Origin origin = entry.getValue();
            locationsConfig.set(base + ".world", origin.worldName);
            locationsConfig.set(base + ".x", origin.x);
            locationsConfig.set(base + ".y", origin.y);
            locationsConfig.set(base + ".z", origin.z);
            locationsConfig.set(base + ".yaw", origin.yaw);
            locationsConfig.set(base + ".pitch", origin.pitch);
            locationsConfig.set(base + ".gamemode", origin.gameMode == null ? null : origin.gameMode.name());
        }
        try {
            locationsConfig.save(locationsFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save auth_origins.yml!", e);
        }
    }

    /**
     * Records the player's pre-freeze state, but never overwrites an existing entry: a second
     * freeze (a rejoin while still unauthenticated, a failed /sync) would otherwise overwrite the
     * real origin with the auth world's spawn, and the real gamemode with the holding one.
     */
    public void recordIfAbsent(UUID uuid, Location origin, GameMode gameMode) {
        if (uuid == null || origin == null || origin.getWorld() == null) return;
        if (origins.containsKey(uuid)) return;
        origins.put(uuid, new Origin(origin.getWorld().getName(), origin.getX(), origin.getY(),
                origin.getZ(), origin.getYaw(), origin.getPitch(), gameMode));
        save();
    }

    /** True if an origin was recorded, even when the world it referred to no longer exists. */
    public boolean hasOrigin(UUID uuid) {
        return origins.containsKey(uuid);
    }

    /**
     * The recorded origin, or null when nothing was recorded or the world it named is gone (a
     * season reset drops worlds, and callers must fall back rather than teleport into nothing).
     */
    public Location getOrigin(UUID uuid) {
        Origin origin = origins.get(uuid);
        if (origin == null) return null;
        World world = Bukkit.getWorld(origin.worldName);
        if (world == null) return null;
        return new Location(world, origin.x, origin.y, origin.z, origin.yaw, origin.pitch);
    }

    /** The gamemode the player was in before being parked, or null if it was not recorded. */
    public GameMode getGameMode(UUID uuid) {
        Origin origin = origins.get(uuid);
        return (origin == null) ? null : origin.gameMode;
    }

    public void clearOrigin(UUID uuid) {
        if (origins.remove(uuid) != null) {
            save();
        }
    }

    /** Unknown or absent values return null rather than failing the load. */
    private static GameMode parseGameMode(String name) {
        if (name == null) return null;
        try {
            return GameMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
