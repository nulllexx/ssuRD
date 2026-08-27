package org.raindrippy.serversideutils;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.json.simple.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AuthService {
    /** Vanilla default; the old freeze rooted players at 0 and unfreeze restored this value. */
    private static final float DEFAULT_WALK_SPEED = 0.2f;

    /** How far a parked player may roam from the auth world spawn before being pulled back. */
    private static final double LEASH_RADIUS = 50.0;
    private static final double LEASH_RADIUS_SQUARED = LEASH_RADIUS * LEASH_RADIUS;

    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Map<UUID, GameMode> gameModeMap = new HashMap<>();

    private final ApiClient apiClient;
    private final CredentialsManager credentialsManager;
    private final CryptoService cryptoService;
    private final NetworkGuard networkGuard;
    private final Logger logger;
    /** Null when no auth world is configured; the freeze then leaves the player where they are. */
    private final AuthLocationsManager authLocations;
    private final String authWorldName;

    public AuthService(ApiClient apiClient,
                       CredentialsManager credentialsManager,
                       CryptoService cryptoService) {
        this(apiClient, credentialsManager, cryptoService, null);
    }

    public AuthService(ApiClient apiClient,
                       CredentialsManager credentialsManager,
                       CryptoService cryptoService,
                       Logger logger) {
        this(apiClient, credentialsManager, cryptoService, logger, null, null);
    }

    public AuthService(ApiClient apiClient,
                       CredentialsManager credentialsManager,
                       CryptoService cryptoService,
                       Logger logger,
                       AuthLocationsManager authLocations,
                       String authWorldName) {
        this.apiClient = apiClient;
        this.credentialsManager = credentialsManager;
        this.cryptoService = cryptoService;
        this.networkGuard = new NetworkGuard(cryptoService);
        // In production Main injects the plugin logger; fall back to a class logger for tests.
        this.logger = (logger != null) ? logger : Logger.getLogger(AuthService.class.getName());
        this.authLocations = authLocations;
        this.authWorldName = authWorldName;
    }

    public boolean isFrozen(UUID uuid) {
        return frozenPlayers.contains(uuid);
    }

    public Map<UUID, GameMode> getGameModeMap() {
        return gameModeMap;
    }

    public void freezePlayer(Player player) {
        freezePlayer(player, "You need to authenticate to access this server.");
    }

    /**
     * Freezes the player with a caller-supplied explanation, so a re-sync forced by an unrecognised
     * network can say so instead of reading like a first-time login prompt.
     */
    public void freezePlayer(Player player, String reason) {
        UUID playerUUID = player.getUniqueId();
        // Captured before anything is changed, so what gets stored is the mode they were playing in.
        GameMode previousMode = player.getGameMode();
        frozenPlayers.add(playerUUID);
        moveToAuthWorld(player, previousMode);

        // Adventure, not spectator: players need to walk around the waiting chamber, and a
        // spectator would fly straight through its walls and could use the spectate menu to
        // teleport to a real player -- showing them exactly what the auth world exists to hide.
        player.setGameMode(GameMode.ADVENTURE);
        // Clear the old hold (blind + rooted) so a player carried over from a previous version,
        // or from a session before this one, can actually see and use the chamber.
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.setWalkSpeed(DEFAULT_WALK_SPEED);

        player.sendMessage(ChatColor.RED + reason);
        player.sendMessage(ChatColor.YELLOW + "Use /sync <username> <password> to authenticate.");
    }

    /**
     * Parks an unauthenticated player in the auth world, remembering where they came from. The auth
     * world runs with {@code reducedDebugInfo}, so the coordinates they can read there are both
     * blanked on F3 and meaningless — someone who has not proven who they are never sees where this
     * account's base is.
     */
    private void moveToAuthWorld(Player player, GameMode previousMode) {
        if (authLocations == null || authWorldName == null) return;

        World authWorld = Bukkit.getWorld(authWorldName);
        if (authWorld == null) {
            // Fail safe: the player stays frozen, they are just not moved. Loud, because the
            // coordinate protection is silently absent until someone creates the world.
            logger.severe("Auth world '" + authWorldName + "' does not exist, so frozen players stay"
                    + " at their real position and can still read their coordinates. Create the world"
                    + " (the plugin sets reducedDebugInfo on it automatically) to close this gap.");
            return;
        }
        // Already parked (a rejoin while still unauthenticated, or a failed /sync): moving again
        // would record the auth world itself as the origin and strand them there.
        if (authWorld.equals(player.getWorld())) return;

        authLocations.recordIfAbsent(player.getUniqueId(), player.getLocation(), previousMode);
        player.teleport(authWorld.getSpawnLocation());
    }

    /**
     * Pulls a parked player back to the auth world spawn if they have wandered well clear of the
     * waiting chamber. This is only a backstop — the chamber's own walls are the real containment —
     * so that the waiting area still works if the build has a gap in it.
     *
     * @return true if the player was pulled back
     */
    public boolean keepInChamber(Player player) {
        if (authWorldName == null || !isFrozen(player.getUniqueId())) return false;

        World world = player.getWorld();
        // Not in the auth world at all (no auth world configured, or it was missing at freeze
        // time): there is no chamber to hold them in, so leave them alone.
        if (!authWorldName.equals(world.getName())) return false;

        Location spawn = world.getSpawnLocation();
        if (player.getLocation().distanceSquared(spawn) <= LEASH_RADIUS_SQUARED) return false;

        player.teleport(spawn);
        player.sendMessage(ChatColor.YELLOW + "Please wait here until you've authenticated.");
        return true;
    }

    /**
     * Puts a newly authenticated player back where they were before being parked. Falls back to the
     * main world when the recorded world is gone (a season reset drops worlds) and when a player is
     * somehow in the auth world with nothing recorded, so nobody is ever left stranded there.
     */
    private void restoreFromAuthWorld(Player player) {
        if (authLocations == null || authWorldName == null) return;

        UUID uuid = player.getUniqueId();
        Location origin = authLocations.getOrigin(uuid);
        if (origin != null) {
            player.teleport(origin);
            authLocations.clearOrigin(uuid);
            return;
        }

        boolean strandedInAuthWorld = authWorldName.equals(player.getWorld().getName());
        if (authLocations.hasOrigin(uuid) || strandedInAuthWorld) {
            World mainWorld = Bukkit.getWorld("world");
            if (mainWorld != null) {
                player.teleport(mainWorld.getSpawnLocation());
                player.sendMessage(ChatColor.YELLOW
                        + "Your previous location is no longer available; you've been sent to spawn.");
            } else {
                logger.severe("Cannot return " + player.getName()
                        + " from the auth world: neither the recorded world nor 'world' exists.");
            }
        }
        authLocations.clearOrigin(uuid);
    }

    public void unfreezePlayer(Player p) {
        UUID playerUUID = p.getUniqueId();
        frozenPlayers.remove(playerUUID);
        GameMode defMode = resolveRestoredMode(playerUUID);
        // Clears the old freeze's hold for anyone still carrying it from a previous version.
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        p.setWalkSpeed(DEFAULT_WALK_SPEED);
        p.sendMessage(ChatColor.GREEN + "Welcome! You're able to access the server.");
        p.setGameMode(defMode);
        // Restore the gamemode first: teleporting out of the auth world should land them in the
        // mode they will actually play in, not the holding one.
        restoreFromAuthWorld(p);
        gameModeMap.remove(playerUUID);
    }

    /**
     * Works out which gamemode a newly authenticated player should land in.
     *
     * <p>The persisted value is captured before the freeze and is authoritative when present. The
     * in-memory map is only a fallback, and cannot be trusted blindly: it is written on every join,
     * so a player who rejoined while still parked has the holding gamemode recorded there. Handing
     * that back would leave an authenticated player stuck in adventure mode.
     */
    private GameMode resolveRestoredMode(UUID playerUUID) {
        if (authLocations != null) {
            GameMode persisted = authLocations.getGameMode(playerUUID);
            if (persisted != null) return persisted;
        }
        GameMode recorded = gameModeMap.get(playerUUID);
        if (recorded == null || recorded == GameMode.SPECTATOR || recorded == GameMode.ADVENTURE) {
            return GameMode.SURVIVAL;
        }
        return recorded;
    }

    /**
     * The RainDrippy account this character is bound to, or null if it has never been synced.
     * The binding outlives a stale password: only staff (/unlink) may re-point it.
     */
    private String boundUsername(UUID playerUUID) {
        JSONObject creds = credentialsManager.getCredentials().get(playerUUID);
        return (creds == null) ? null : (String) creds.get("username");
    }

    @SuppressWarnings("unchecked")
    public void handleSync(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(ChatColor.RED + "Usage: /sync <username> <password>");
            return;
        }
        if (!isFrozen(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You are already authenticated.");
            return;
        }
        String username = args[0];
        String password = args[1];

        // A character stays bound to the RainDrippy account it was first synced with. Without this
        // the guard only proves "you own some member account", so anyone who got hold of the
        // Minecraft account could sync their own login and play this character -- exactly the
        // takeover the sync system exists to stop. Checked before the API call so the server is
        // not usable as a credential-checking oracle for arbitrary accounts.
        String boundUsername = boundUsername(player.getUniqueId());
        if (boundUsername != null && !boundUsername.equalsIgnoreCase(username)) {
            logger.warning("Rejected /sync for " + player.getName() + ": character is bound to a"
                    + " different RainDrippy account (attempted '" + username + "').");
            player.sendMessage(ChatColor.RED + "This character is linked to a different RainDrippy account.");
            player.sendMessage(ChatColor.YELLOW + "Sign in with the account you first synced with, "
                    + "or ask staff to unlink it.");
            return;
        }

        ApiClient.LoginResult result = apiClient.queryCredentials(username, password);
        switch (result) {
            case SUCCESS:
                String encryptedPassword;
                try {
                    encryptedPassword = cryptoService.encrypt(password);
                } catch (RuntimeException e) {
                    // Encryption misconfigured: refuse to store credentials rather than risk plaintext.
                    // Log the failure (never the password) so this stops being an invisible "server error".
                    logger.log(Level.SEVERE,
                            "Encryption failed while saving login for " + player.getName()
                                    + "; refusing to store credentials", e);
                    player.sendMessage(ChatColor.RED + "A server error prevented saving your login. Please contact staff.");
                    return;
                }
                // Reuse the existing entry when there is one so previously remembered networks
                // survive a re-sync instead of being replaced by a fresh object.
                JSONObject playerCreds = credentialsManager.getCredentials().get(player.getUniqueId());
                if (playerCreds == null) playerCreds = new JSONObject();
                playerCreds.put("username", username);
                playerCreds.put("password", encryptedPassword);
                // The player just proved the account password from this network; trust it from now on.
                networkGuard.remember(playerCreds, networkGuard.fingerprint(player));
                credentialsManager.getCredentials().put(player.getUniqueId(), playerCreds);
                credentialsManager.save();

                unfreezePlayer(player);
                player.sendMessage(ChatColor.GREEN + "Authentication successful! Your credentials have been saved.");
                break;
            case INVALID_CREDENTIALS:
                player.sendMessage(ChatColor.RED + "Incorrect username or password. Please double-check your credentials and try again.");
                break;
            case NOT_MEMBER:
                player.sendMessage(ChatColor.RED + "Those credentials are valid, but your account isn't a member of this server.");
                break;
            default:
                player.sendMessage(ChatColor.RED + "A server error occurred while authenticating. Please try again later.");
                break;
        }
    }
}
