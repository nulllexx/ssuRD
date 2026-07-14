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
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
    private final CombatManager combatManager;
    private final CombatLogManager combatLogManager;
    private final WarningsManager warningsManager;
    private final Set<String> hiddenCommands;

    private static final Set<String> COMBAT_BLOCKED = new HashSet<>(Arrays.asList("home", "suicide"));

    public PluginEventListener(Main plugin,
                               AuthService authService,
                               CredentialsManager credentialsManager,
                               CryptoService cryptoService,
                               ApiClient apiClient,
                               ScoreboardService scoreboardService,
                               ConfigManager configManager,
                               CombatManager combatManager,
                               CombatLogManager combatLogManager,
                               WarningsManager warningsManager,
                               Set<String> hiddenCommands) {
        this.plugin = plugin;
        this.authService = authService;
        this.credentialsManager = credentialsManager;
        this.cryptoService = cryptoService;
        this.apiClient = apiClient;
        this.scoreboardService = scoreboardService;
        this.configManager = configManager;
        this.combatManager = combatManager;
        this.combatLogManager = combatLogManager;
        this.warningsManager = warningsManager;
        this.hiddenCommands = hiddenCommands;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        GameMode mode = player.getGameMode();
        authService.getGameModeMap().put(playerUUID, mode);
        scoreboardService.enable(player);

        if (combatLogManager.isPending(playerUUID)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                player.sendMessage(ChatColor.RED + "You combat-logged last session. This is strike #"
                        + combatLogManager.getStrikes(playerUUID) + ". A formal warning has been filed.");
                combatLogManager.clearPending(playerUUID);
            }, 40L);
        }

        if (credentialsManager.getCredentials().containsKey(playerUUID)) {
            JSONObject creds = credentialsManager.getCredentials().get(playerUUID);
            String savedUsername = (String) creds.get("username");
            String encryptedPassword = (String) creds.get("password");

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    // Decrypt inside the guarded block: a corrupt/misconfigured credential
                    // throws here and falls through to freezing the player (fail safe).
                    String plainPassword = cryptoService.decrypt(encryptedPassword).trim().strip();
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
            return;
        }
        if (COMBAT_BLOCKED.contains(baseCommand) && combatManager.isInCombat(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "You can't use /" + baseCommand
                    + " while in combat! (" + combatManager.getRemainingSeconds(event.getPlayer()) + "s left)");
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
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) return;

        if (authService.isFrozen(attacker.getUniqueId())) {
            event.setCancelled(true);
            attacker.sendMessage(ChatColor.RED + "You can't attack until you log in.");
            return;
        }

        if (event.getEntity() instanceof Player) {
            Player victim = (Player) event.getEntity();
            if (!victim.getUniqueId().equals(attacker.getUniqueId())) {
                tagCombat(attacker);
                tagCombat(victim);
            }
        }
    }

    private Player resolveAttacker(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player) {
            return (Player) damager;
        }
        if (damager instanceof Projectile) {
            ProjectileSource shooter = ((Projectile) damager).getShooter();
            if (shooter instanceof Player) {
                return (Player) shooter;
            }
        }
        return null;
    }

    private void tagCombat(Player player) {
        if (!combatManager.isInCombat(player)) {
            player.sendMessage(ChatColor.RED + "You are now in combat for 15s.");
        }
        combatManager.tagPlayer(player.getUniqueId());
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
        Player player = event.getPlayer();
        if (combatManager.isInCombat(player)) {
            UUID uuid = player.getUniqueId();
            int n = combatLogManager.recordStrike(uuid);
            warningsManager.getWarnings()
                    .computeIfAbsent(uuid, k -> new ArrayList<>())
                    .add("Combat logging (strike #" + n + ")");
            warningsManager.save();
            combatManager.clearCombat(player);
            plugin.getLogger().warning(player.getName() + " combat-logged (strike #" + n + ").");

            if (n >= 3) {
                JSONObject creds = credentialsManager.getCredentials().get(uuid);
                if (creds != null) {
                    String username = (String) creds.get("username");
                    plugin.getLogger().warning("Strike #" + n + " for " + player.getName()
                            + " -> requesting 3-day ban for account " + username + ".");
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        boolean ok = apiClient.banAccount(username);
                        // Player.kickPlayer must run on the main thread; hop back before kicking.
                        Bukkit.getScheduler().runTask(plugin,
                                () -> player.kickPlayer("Possible change of access"));
                        plugin.getLogger().warning("3-day ban for account " + username
                                + " (strike #" + n + "): " + (ok ? "success" : "FAILED"));
                    });
                } else {
                    plugin.getLogger().warning("Cannot ban combat-logger " + player.getName()
                            + " (strike #" + n + "): no linked credentials.");
                }
            }
        }
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
