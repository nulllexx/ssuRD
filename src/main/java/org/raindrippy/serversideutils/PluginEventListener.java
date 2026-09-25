package org.raindrippy.serversideutils;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

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
    private final PendingPaymentsManager pendingPaymentsManager;
    private final NetworkGuard networkGuard;

    private static final Set<String> COMBAT_BLOCKED = new HashSet<>(Arrays.asList("home", "suicide"));

    /** The only command an unauthenticated player is allowed to run. */
    private static final String AUTH_COMMAND = "sync";

    /** Individual payment lines shown on join before the rest are collapsed into a summary. */
    private static final int MAX_PAYMENT_LINES = 5;

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
                               PendingPaymentsManager pendingPaymentsManager) {
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
        this.pendingPaymentsManager = pendingPaymentsManager;
        this.networkGuard = new NetworkGuard(cryptoService);
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

        if (pendingPaymentsManager.hasPending(playerUUID)) {
            // Same 2-second delay as the combat-log notice so it isn't lost in join spam. The money
            // was already deposited when /sendmoney ran; this only tells them about it.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                List<PendingPaymentsManager.PendingPayment> pending = pendingPaymentsManager.getPending(playerUUID);
                double total = 0;
                for (PendingPaymentsManager.PendingPayment payment : pending) {
                    total += payment.getAmount();
                }
                player.sendMessage(ChatColor.GREEN + "You received $" + PluginCommandHandler.formatAmount(total)
                        + " while you were offline:");
                for (int i = 0; i < pending.size(); i++) {
                    if (i == MAX_PAYMENT_LINES) {
                        player.sendMessage(ChatColor.GRAY + "  ...and " + (pending.size() - i) + " more.");
                        break;
                    }
                    PendingPaymentsManager.PendingPayment payment = pending.get(i);
                    player.sendMessage(ChatColor.GRAY + "  $" + PluginCommandHandler.formatAmount(payment.getAmount())
                            + " from " + payment.getFrom());
                }
                pendingPaymentsManager.clearPending(playerUUID);
            }, 40L);
        }

        if (credentialsManager.getCredentials().containsKey(playerUUID)) {
            JSONObject creds = credentialsManager.getCredentials().get(playerUUID);
            String savedUsername = (String) creds.get("username");
            String encryptedPassword = (String) creds.get("password");

            // Bound to an account but holding no usable secret (the stored password went stale on
            // a previous join). They must sign in again -- as the same account, which the binding
            // in AuthService.handleSync enforces.
            if (encryptedPassword == null) {
                authService.freezePlayer(player,
                        "Your saved login is no longer valid. Please sign in again.");
                return;
            }

            // Saved credentials only auto-authenticate from a network the player has synced from
            // before. An unfamiliar one (or an address we can't read) falls back to /sync. The
            // credentials are kept: the ban pipeline still needs the username, and a successful
            // /sync just refreshes this entry.
            String fingerprint = networkGuard.fingerprint(player);
            if (!networkGuard.isKnown(creds, fingerprint)) {
                plugin.getLogger().warning(player.getName()
                        + " joined from an unrecognised network; requiring /sync.");
                authService.freezePlayer(player, "New sign-in location detected. "
                        + "Please confirm it's you before continuing.");
                return;
            }

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
                            // Drop the stale secret but KEEP the username binding. The usual cause
                            // is the owner changing their password on the website; deleting the
                            // whole entry would unbind the character and let the next person to
                            // join it link their own account instead.
                            creds.remove("password");
                            credentialsManager.save();
                            authService.freezePlayer(player,
                                    "Your saved login is no longer valid. Please sign in again.");
                        });
                    }
                } catch (Exception e) {
                    // Never log the password; freeze on any failure verifying saved credentials.
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to verify saved credentials for " + player.getName() + "; freezing", e);
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

        // Parked players may only authenticate. Now that they can walk around the chamber, an
        // unblocked teleport command from any other plugin (/home, /spawn, /tp) would take them
        // straight out of it and into the world the auth world exists to keep them out of.
        if (!AUTH_COMMAND.equals(baseCommand) && authService.isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED
                    + "You need to authenticate before using commands.");
            event.getPlayer().sendMessage(ChatColor.YELLOW
                    + "Use /sync <username> <password> to authenticate.");
            return;
        }

        // /sync is handled here instead of through the command map, and cancelled so the username
        // and password never enter the dispatch path. Every other command runs normally -- keeping
        // the likes of /tell out of the log files is CommandLogFilter's job, and cancelling them
        // here silently swallowed the messages instead of just hiding them.
        if (AUTH_COMMAND.equals(baseCommand)) {
            event.setCancelled(true);
            // Split the original message, not the lower-cased copy: passwords are case-sensitive.
            String[] parts = event.getMessage().substring(1).split(" ");
            authService.handleSync(event.getPlayer(), Arrays.copyOfRange(parts, 1, parts.length));
            return;
        }
        if (COMBAT_BLOCKED.contains(baseCommand) && combatManager.isInCombat(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "You can't use /" + baseCommand
                    + " while in combat! (" + combatManager.getRemainingSeconds(event.getPlayer()) + "s left)");
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
                    .add(CombatLogManager.warningText(n));
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

    /**
     * Movement itself is no longer cancelled — parked players are meant to walk around the waiting
     * chamber. This only acts as a backstop, pulling back anyone who has left it entirely.
     */
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        Location from = event.getFrom();
        // Looking around is not going anywhere; skip the check unless the block position changed.
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        authService.keepInChamber(event.getPlayer());
    }
}
