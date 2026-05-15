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

public class AuthService {
    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Map<UUID, GameMode> gameModeMap = new HashMap<>();

    private final ApiClient apiClient;
    private final CredentialsManager credentialsManager;
    private final CryptoService cryptoService;

    public AuthService(ApiClient apiClient,
                       CredentialsManager credentialsManager,
                       CryptoService cryptoService) {
        this.apiClient = apiClient;
        this.credentialsManager = credentialsManager;
        this.cryptoService = cryptoService;
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
        if (defMode.equals(GameMode.SPECTATOR)) defMode = GameMode.SURVIVAL;
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
        if (apiClient.queryLogin(username, password)) {
            JSONObject playerCreds = new JSONObject();
            playerCreds.put("username", username);
            playerCreds.put("password", cryptoService.encrypt(password));
            credentialsManager.getCredentials().put(player.getUniqueId(), playerCreds);
            credentialsManager.save();

            unfreezePlayer(player);
            player.sendMessage(ChatColor.GREEN + "Authentication successful! Your credentials have been saved.");
        } else {
            player.sendMessage(ChatColor.RED + "Authentication failed. Please check your credentials.");
        }
    }
}
