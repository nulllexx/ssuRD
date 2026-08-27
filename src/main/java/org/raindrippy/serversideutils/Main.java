package org.raindrippy.serversideutils;

import io.github.cdimascio.dotenv.Dotenv;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.simple.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class Main extends JavaPlugin {
    private static final Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();
    private static final String AES_KEY = dotenv.get("AES_KEY");
    private static final String ADMIN_USER = dotenv.get("ADMIN_USER");
    private static final String ADMIN_PWD = dotenv.get("ADMIN_PWD");

    private static final int SEASON = 9;
    private static final String THEME = "S8 reboot";
    private static final boolean DIGITAL_ECONOMY_ENABLED = false;
    private static final Set<String> HIDDEN_COMMANDS = new HashSet<>(Arrays.asList("sync", "tell", "msg", "w", "whisper", "r", "reply"));
    /**
     * World unauthenticated players are parked in. It must exist on the server (create it with
     * Multiverse); the plugin turns reducedDebugInfo on for it so the F3 screen there shows no
     * coordinates. If it is missing, players are still frozen — they are just not moved.
     */
    private static final String AUTH_WORLD = "authhub";

    private Economy econ;

    private CryptoService cryptoService;
    private ApiClient apiClient;
    private ConfigManager configManager;
    private WarningsManager warningsManager;
    private CredentialsManager credentialsManager;
    private AuthService authService;
    private ScoreboardService scoreboardService;
    private CombatManager combatManager;
    private CombatLogManager combatLogManager;
    private PendingPaymentsManager pendingPaymentsManager;
    private AuthLocationsManager authLocationsManager;

    @Override
    public void onEnable() {
        cryptoService = new CryptoService(AES_KEY);
        apiClient = new ApiClient(ADMIN_USER, ADMIN_PWD, getLogger());

        warningsManager = new WarningsManager(this);
        warningsManager.setup();
        warningsManager.load();

        credentialsManager = new CredentialsManager(this);
        credentialsManager.setup();
        credentialsManager.load();

        combatManager = new CombatManager();

        combatLogManager = new CombatLogManager(this);
        combatLogManager.setup();
        combatLogManager.load();

        pendingPaymentsManager = new PendingPaymentsManager(this);
        pendingPaymentsManager.setup();
        pendingPaymentsManager.load();

        authLocationsManager = new AuthLocationsManager(this);
        authLocationsManager.setup();
        authLocationsManager.load();

        configManager = new ConfigManager(this);

        if (!setupEconomy()) {
            getLogger().severe("Vault economy not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        scoreboardService = new ScoreboardService(econ, DIGITAL_ECONOMY_ENABLED, SEASON, THEME);
        if (scoreboardService.getManager() == null) {
            getLogger().severe("ScoreboardManager is null! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        authService = new AuthService(apiClient, credentialsManager, cryptoService, getLogger(),
                authLocationsManager, AUTH_WORLD);
        prepareAuthWorld();

        configManager.loadAll();

        World world = Bukkit.getWorlds().get(0);
        long newseed = world.getSeed();
        long savedSeed = configManager.loadSeed();
        if ((newseed == savedSeed && newseed != 0L) && configManager.isLifestealReviveEnabled()) {
            for (OfflinePlayer offP : Bukkit.getOfflinePlayers()) {
                if (offP.hasPlayedBefore()) {
                    String username = offP.getName();
                    if (username != null) {
                        String cmd = "lifesteal reset " + username;
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    }
                }
            }
        }
        configManager.saveSeed(newseed);

        PluginEventListener listener = new PluginEventListener(
                this, authService, credentialsManager, cryptoService,
                apiClient, scoreboardService, configManager,
                combatManager, combatLogManager, warningsManager, pendingPaymentsManager,
                HIDDEN_COMMANDS);
        Bukkit.getPluginManager().registerEvents(listener, this);

        if (configManager.isTrackingEnabled()) configManager.savePlayerCount();

        PluginCommandHandler commandHandler = new PluginCommandHandler(
                warningsManager, scoreboardService, configManager, pendingPaymentsManager,
                credentialsManager, authService, econ, DIGITAL_ECONOMY_ENABLED);

        CommandTabCompleter tabCompleter = new CommandTabCompleter();
        for (String cmd : new String[]{
                "eventhub", "return", "togglescoreboard", "sendmoney",
                "warn", "getwarnings", "removewarning", "clearwarnings",
                "owarn", "oclearwarnings", "oremovewarning",    
                "setcounter", "lsrev", "sync", "unlink"}) {
            this.getCommand(cmd).setExecutor(commandHandler);
            this.getCommand(cmd).setTabCompleter(tabCompleter);
        }

        installCommandLogFilter();

        new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID uuid : new HashSet<>(scoreboardService.getScoreboardPlayers())) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        player.setScoreboard(scoreboardService.build(player));
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L);

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID playerUUID = player.getUniqueId();

                    if (!authService.isFrozen(playerUUID)
                            && credentialsManager.getCredentials().containsKey(playerUUID)) {
                        JSONObject creds = credentialsManager.getCredentials().get(playerUUID);
                        String savedUsername = (String) creds.get("username");

                        Bukkit.getScheduler().runTaskAsynchronously(Main.this, () -> {
                            if (!apiClient.queryStatus(savedUsername, player)) {
                                Bukkit.getScheduler().runTask(Main.this, () -> {
                                    // Invalidate the stored password but KEEP the username binding.
                                    // Removing the entry would unbind the character, so a banned
                                    // player could simply sync a fresh RainDrippy account onto it
                                    // and walk straight back in.
                                    creds.remove("password");
                                    credentialsManager.save();
                                });
                            }
                        });
                    }
                }
            }
        }.runTaskTimer(this, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        if (configManager != null) configManager.savePlayerCount();
        if (credentialsManager != null) credentialsManager.save();
        if (combatLogManager != null) combatLogManager.save();
        if (pendingPaymentsManager != null) pendingPaymentsManager.save();
        if (authLocationsManager != null) authLocationsManager.save();
    }

    /**
     * Installs {@link CommandLogFilter} on the Log4j2 root logger so lines echoing sensitive
     * commands (e.g. {@code /sync <user> <pass>}) never reach the console or log files. This is
     * the layer Paper/Spigot actually log command echoes on; a java.util.logging filter does not
     * see them. Fails loud rather than silently leaking if the backend is not Log4j2.
     */
    private void installCommandLogFilter() {
        try {
            org.apache.logging.log4j.core.Logger rootLogger =
                    (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager
                            .getRootLogger();
            rootLogger.addFilter(new CommandLogFilter(HIDDEN_COMMANDS));
        } catch (Throwable t) {
            getLogger().severe("Failed to install command log filter; sensitive commands (e.g. "
                    + "/sync credentials) may leak into server logs: " + t);
        }
    }

    /**
     * Turns on {@code reducedDebugInfo} for the auth world so the F3 screen shows no coordinates
     * while a player is waiting to authenticate. Done here rather than left to a server admin: the
     * protection is invisible when it is missing, so the plugin that relies on it sets it itself.
     */
    private void prepareAuthWorld() {
        World authWorld = Bukkit.getWorld(AUTH_WORLD);
        if (authWorld == null) {
            getLogger().severe("Auth world '" + AUTH_WORLD + "' not found. Unauthenticated players "
                    + "will still be frozen, but they stay at their real position and can read their "
                    + "coordinates. Create the world (e.g. /mv create " + AUTH_WORLD + " normal) and restart.");
            return;
        }
        if (Boolean.TRUE.equals(authWorld.getGameRuleValue(GameRule.REDUCED_DEBUG_INFO))) {
            return;
        }
        if (authWorld.setGameRule(GameRule.REDUCED_DEBUG_INFO, true)) {
            getLogger().info("Enabled reducedDebugInfo on '" + AUTH_WORLD + "'.");
        } else {
            getLogger().severe("Could not enable reducedDebugInfo on '" + AUTH_WORLD
                    + "'; coordinates will still be visible on F3 while players wait to authenticate.");
        }
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return econ != null;
    }
}
