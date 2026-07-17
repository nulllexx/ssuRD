package org.raindrippy.serversideutils;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
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
    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Map<UUID, GameMode> gameModeMap = new HashMap<>();

    private final ApiClient apiClient;
    private final CredentialsManager credentialsManager;
    private final CryptoService cryptoService;
    private final Logger logger;

    public AuthService(ApiClient apiClient,
                       CredentialsManager credentialsManager,
                       CryptoService cryptoService) {
        this(apiClient, credentialsManager, cryptoService, null);
    }

    public AuthService(ApiClient apiClient,
                       CredentialsManager credentialsManager,
                       CryptoService cryptoService,
                       Logger logger) {
        this.apiClient = apiClient;
        this.credentialsManager = credentialsManager;
        this.cryptoService = cryptoService;
        // In production Main injects the plugin logger; fall back to a class logger for tests.
        this.logger = (logger != null) ? logger : Logger.getLogger(AuthService.class.getName());
    }

    public boolean isFrozen(UUID uuid) {
        return frozenPlayers.contains(uuid);
    }

    public Map<UUID, GameMode> getGameModeMap() {
        return gameModeMap;
    }

    public void freezePlayer(Player player) {
        UUID playerUUID = player.getUniqueId();
        frozenPlayers.add(playerUUID);
        player.setGameMode(GameMode.SPECTATOR);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0));
        player.setWalkSpeed(0.0f);
        player.sendMessage(ChatColor.RED + "You need to authenticate to access this server.");
        player.sendMessage(ChatColor.YELLOW + "Use /sync <username> <password> to authenticate.");
    }

    public void unfreezePlayer(Player p) {
        UUID playerUUID = p.getUniqueId();
        frozenPlayers.remove(playerUUID);
        GameMode defMode = gameModeMap.get(playerUUID);
        // No recorded gamemode (e.g. authenticated without a prior join) -> default to survival.
        if (defMode == null || defMode.equals(GameMode.SPECTATOR)) defMode = GameMode.SURVIVAL;
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        p.setWalkSpeed(0.2f);
        p.sendMessage(ChatColor.GREEN + "Welcome! You're able to access the server.");
        p.setGameMode(defMode);
        gameModeMap.remove(playerUUID);
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
                JSONObject playerCreds = new JSONObject();
                playerCreds.put("username", username);
                playerCreds.put("password", encryptedPassword);
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
