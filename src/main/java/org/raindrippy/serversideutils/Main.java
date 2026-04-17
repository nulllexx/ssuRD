package org.raindrippy.serversideutils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Filter;
import java.util.logging.LogRecord;
import java.io.FileWriter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scheduler.BukkitRunnable;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import io.github.cdimascio.dotenv.Dotenv;

import com.google.gson.Gson;
public class Main extends JavaPlugin implements CommandExecutor, Listener {
	private final File jsonFile = new File(System.getProperty("user.dir"), "plrCount.json");
	private static final Dotenv dotenv = Dotenv.load();
	private static final String AES_KEY = dotenv.get("AES_KEY");
    private static final String OBJECTIVE_NAME = "balance";
    private File warningsFile, confFile, credsFile;
    private FileConfiguration warningsConfig;
    // We'll track players (by UUID) who have the custom scoreboard enabled.
    private final Set<UUID> scoreboardPlayers = new HashSet<>();
    private ScoreboardManager scoreboardManager;
    private final Map<UUID, List<String>> playerWarnings = new HashMap<>();
    private final Map<String, Object> lifesteal = new HashMap<>();
    private boolean trackingEnabled = true, lifestealReviveEnabled = false;
    private final boolean digitalEconomyEnabled = true;
    private final Set<UUID> frozenPlayers = new HashSet<>();
    private final Map<UUID, JSONObject> playerCredentials = new HashMap<>();
    private final int season = 7;
    private final String theme = "Science";
    private final Set<String> hiddenCommands = new HashSet<>(Arrays.asList(
            "sync"
        ));
    // Vault Economy instance.
    private Economy econ;
    // Gamemode map
    private Map<UUID, GameMode> gameModeMap = new HashMap<>();
    @Override
    public void onEnable() {
        setupWarningsFile();
        loadWarningsFromFile();
        setupCredentialsFile();
        loadCredentialsFromFile();
        // Setup Vault economy
        if (!setupEconomy()) {
            getLogger().severe("Vault economy not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // Initialize the scoreboard manager.
        scoreboardManager = Bukkit.getScoreboardManager();
        if (scoreboardManager == null) {
            getLogger().severe("ScoreboardManager is null! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        trackingEnabled = loadTracking();
        lifestealReviveEnabled = loadLsRev();
        World world = Bukkit.getWorlds().get(0);
        long newseed = world.getSeed();
        long savedSeed = loadSeed();
        if ((newseed == savedSeed && newseed != 0L) && lifestealReviveEnabled) {
        	for (OfflinePlayer offP : Bukkit.getOfflinePlayers()) {
        	    if (offP.hasPlayedBefore()) {
        	        String username = offP.getName();
        	        if (username != null) {
        	            String cmd = "lifesteal reset " + username; // Lifesteal 1.20-1.21.5 SUPPORT plugin
        	            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        	        }
        	    }
        	}
        }
        saveSeed(newseed);
        Bukkit.getPluginManager().registerEvents(this, this);
        if (trackingEnabled) savePlayerCount();
        // Register commands and events.
        // Note: sendmoney command now uses Vault.
        this.getCommand("eventhub").setExecutor(this);
        this.getCommand("return").setExecutor(this);
        this.getCommand("togglescoreboard").setExecutor(this);
        this.getCommand("sendmoney").setExecutor(this);
        this.getCommand("warn").setExecutor(this);
        this.getCommand("getwarnings").setExecutor(this);
        this.getCommand("removewarning").setExecutor(this);
        this.getCommand("clearwarnings").setExecutor(this);
        this.getCommand("owarn").setExecutor(this);
        this.getCommand("oclearwarnings").setExecutor(this);
        this.getCommand("oremovewarning").setExecutor(this);
        this.getCommand("setcounter").setExecutor(this);
        this.getCommand("lsrev").setExecutor(this);
        this.getCommand("sync").setExecutor(this);
        this.getCommand("warn").setTabCompleter(new CommandTabCompleter());
        this.getCommand("owarn").setTabCompleter(new CommandTabCompleter());
        this.getCommand("getwarnings").setTabCompleter(new CommandTabCompleter());
        this.getCommand("clearwarnings").setTabCompleter(new CommandTabCompleter());
        this.getCommand("removewarning").setTabCompleter(new CommandTabCompleter());
        this.getCommand("oremovewarning").setTabCompleter(new CommandTabCompleter());
        this.getCommand("setcounter").setTabCompleter(new CommandTabCompleter());
        this.getCommand("lsrev").setTabCompleter(new CommandTabCompleter());
        getLogger().getParent().setFilter(new CommandLogFilter()); // Don't log sensitive shit
        // Schedule a repeating task to update the scoreboard for players who have it enabled.
        new BukkitRunnable() {
            @Override
            public void run() {
                // Iterate over a copy to avoid ConcurrentModificationException.
                for (UUID uuid : new HashSet<>(scoreboardPlayers)) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        // Rebuild and update the scoreboard for this player.
                        Scoreboard board = buildScoreboard(player);
                        player.setScoreboard(board);
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L); // update every 20 ticks (1 second)
        new BukkitRunnable() {
            @Override
            public void run() {
                // Check status of all online players who are not frozen (authenticated)
                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID playerUUID = player.getUniqueId();
                    
                    // Only check authenticated players with saved credentials
                    if (!frozenPlayers.contains(playerUUID) && playerCredentials.containsKey(playerUUID)) {
                        JSONObject creds = playerCredentials.get(playerUUID);
                        String savedUsername = (String) creds.get("username");
                        
                        // Run async to avoid blocking main thread
                        Bukkit.getScheduler().runTaskAsynchronously(Main.this, () -> {
                            if (!queryStatus(savedUsername, player)) {
                                // Player kicked, remove credentials on main thread
                                Bukkit.getScheduler().runTask(Main.this, () -> {
                                    playerCredentials.remove(playerUUID);
                                    saveCredentialsToFile();
                                });
                            }
                        });
                    }
                }
            }
        }.runTaskTimer(this, 6000L, 6000L); // Every 5 minutes
    }
    @Override
    public void onDisable() {
    	savePlayerCount();
    	saveCredentialsToFile();
    }
    private String encryptPassword(String password) {
        try {
            SecretKeySpec key = new SecretKeySpec(AES_KEY.getBytes(), "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(password.getBytes());
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            e.printStackTrace();
            return password; // fallback
        }
    }
    
    private String decryptPassword(String encryptedPassword) {
        try {
            SecretKeySpec key = new SecretKeySpec(AES_KEY.getBytes(), "AES");
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] decoded = Base64.getDecoder().decode(encryptedPassword);
            byte[] decrypted = cipher.doFinal(decoded);
            return new String(decrypted);
        } catch (Exception e) {
            e.printStackTrace();
            return encryptedPassword; // fallback
        }
    }
    @SuppressWarnings("unchecked") // shush, sheesh!
	private void savePlayerCount() {
    	int playerCount = Bukkit.getOnlinePlayers().size();
    	JSONObject data = new JSONObject();
    	data.put("players", playerCount);
    	try {
    		if (!jsonFile.exists()) {
    			jsonFile.createNewFile();
    		}
    		try (FileWriter writer = new FileWriter(jsonFile)) {
                writer.write(data.toJSONString());
            }
    	} catch (IOException e) {
    		getLogger().severe("Failed to save player count: " + e.getMessage());
    	}
    }
    private void freezePlayer(Player player) {
        UUID playerUUID = player.getUniqueId();
        frozenPlayers.add(playerUUID);
        player.setGameMode(GameMode.SPECTATOR);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0));
        player.setWalkSpeed(0.0f);
        player.sendMessage(ChatColor.RED + "You need to authenticate to access this server.");
        player.sendMessage(ChatColor.YELLOW + "Use /sync <username> <password> to authenticate.");
    }
    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        // "If it ain't broke, don't fix it"
        RegisteredServiceProvider<Economy> rsp = 
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return econ != null;
    }
    public JSONObject getUserCredentials(String username, JSONArray credentialsArray) {
        for (Object o : credentialsArray) {
            JSONObject userObj = (JSONObject) o;
            if (username.equals(userObj.get("username"))) {
                return userObj; // Found the credentials
            }
        }
        return null; // Not found
    }
    class LoginRequest {
        @SuppressWarnings("unused")
		private String username;
        @SuppressWarnings("unused")
		private String password;
        
        public LoginRequest(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }
    class LoginResponse {
        private boolean isMember;
        
        public boolean isIsMember() {
            return isMember;
        }
    }
    public boolean queryLogin(String username, String password) {
    	try {
            Gson gson = new Gson();
            LoginRequest request = new LoginRequest(username, password);
            String json = gson.toJson(request);
            String response = HttpUtil.postJson("https://bakosmp.go.ro/api/v-creds", json);
            System.out.println("RAW RESPONSE: " + response);  // debug line
            LoginResponse loginResponse = gson.fromJson(response, LoginResponse.class);
            return loginResponse.isIsMember();
            
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    	
    }
    public boolean queryStatus(String username, Player p) {
        try {
            String url = "https://bakosmp.go.ro/api/accstatus-cuser?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8);
            HttpUtil.HttpResponse res = HttpUtil.get(url);
        	if (res.statusCode == 200) return true; else {
        		String l1 = "Account Moderated", reviewed = "Reviewed: null (PDT)", modnote = "Moderator note: null", notice = "Your account has been moderated.";
        		ObjectMapper mapper = new ObjectMapper();
        		@SuppressWarnings("unchecked")
				Map<String, Object> map = mapper.readValue(res.body, Map.class);
        		JSONObject obj = new JSONObject(map);
        		Object banObj = obj.get("banInfo");
        		if (banObj instanceof Map<?, ?>) {
        		    Map<?, ?> banInfo = (Map<?, ?>) banObj;
        		    String type = (String) banInfo.get("type");
        		    reviewed = (String) banInfo.get("moderatedTimePDT") + " (PDT)"; // to this day, i do not know why we use PDT
        		    modnote = (String) banInfo.get("modNote");
        		    switch (type) { // the mother-of-all switch statements
        		    case "1d":
        		    	l1 = "Banned for 1 Day";
        		    	notice = "Your account has been disabled for 1 day.";
        		    	break;
        		    case "3d":
        		    	l1 = "Banned for 3 Days";
        		    	notice = "Your account has been disabled for 3 days.";
        		    	break;
        		    case "7d":
        		    	l1 = "Banned for 7 Days";
        		    	notice = "Your account has been disabled for 7 days.";
        		    	break;
        		    case "14d":
        		    	l1 = "Banned for 14 Days";
        		    	notice = "Your account has been disabled for 14 days.";
        		    	break;
        		    case "perm":
        		    	l1 = "Account Terminated";
        		    	notice = "Your account has been terminated.";
        		    	break;
        		    case "poison":
        		    	l1 = "Account Terminated";
        		    	notice = "Your account has been terminated, and new account creation has been disabled from this device.";
        		    	break;
        		    default:
        		    	l1 = "Account Moderated";
        		    	notice = "Your account has been moderated.";
        		    	break;
        		    }	
        		}
        		String kickMsg = "&l&6" + l1 + "\n&rWe've determined that your prior actions have been against our rules & have taken action against your account.\n" + "&4Reviewed&r: " + reviewed + "\n&4Moderator note&r: " + modnote + "\n\n&l&c" + notice + "\n\n&rIf you wish to appeal, please contact us via Discord or by using one of the methods listed on our website.";
        		kickMsg = ChatColor.translateAlternateColorCodes('&', kickMsg);
        		p.kickPlayer(kickMsg); /// rollback
        		return false;
        	}
        } catch (Exception e) {
            e.printStackTrace();
            return false; // default to deny if API call fails (not like it ever will, because our API is goated!)
        }
    }
    private void setupWarningsFile() {
        warningsFile = new File(getDataFolder(), "warnings.yml");
        if (!warningsFile.exists()) {
            warningsFile.getParentFile().mkdirs();
            saveResource("warnings.yml", false);
        }
        warningsConfig = YamlConfiguration.loadConfiguration(warningsFile);
    }
    private void setupCredentialsFile() {
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
    private void loadWarningsFromFile() {
        for (String uuidString : warningsConfig.getKeys(false)) {
            UUID uuid = UUID.fromString(uuidString);
            List<String> warnings = warningsConfig.getStringList(uuidString);
            playerWarnings.put(uuid, new ArrayList<>(warnings));
        }
    }
    private void loadCredentialsFromFile() {
        if (!credsFile.exists()) return;
        
        try (FileReader reader = new FileReader(credsFile)) {
            JSONParser parser = new JSONParser();
            JSONObject credentialsObj = (JSONObject) parser.parse(reader); // Object, not Array
            
            for (Object key : credentialsObj.keySet()) {
                String uuidString = (String) key;
                JSONObject playerCreds = (JSONObject) credentialsObj.get(uuidString);
                UUID playerUUID = UUID.fromString(uuidString);
                playerCredentials.put(playerUUID, playerCreds);
            }
        } catch (Exception e) {
            getLogger().warning("Failed to load player credentials: " + e.getMessage());
        }
    }
    @SuppressWarnings("unchecked")
    private void saveCredentialsToFile() {
        try {
            JSONObject credentialsObj = new JSONObject();
            
            for (Map.Entry<UUID, JSONObject> entry : playerCredentials.entrySet()) {
                credentialsObj.put(entry.getKey().toString(), entry.getValue());
            }
            
            try (FileWriter writer = new FileWriter(credsFile)) {
                writer.write(credentialsObj.toJSONString());
            }
        } catch (IOException e) {
            getLogger().severe("Failed to save player credentials: " + e.getMessage());
        }
    }
    private void saveWarningsToFile() {
        for (UUID uuid : playerWarnings.keySet()) {
            warningsConfig.set(uuid.toString(), playerWarnings.get(uuid));
        }
        try {
            warningsConfig.save(warningsFile);
        } catch (IOException e) {
            getLogger().severe("Could not save warnings.yml!");
            e.printStackTrace();
        }
    }
	@Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be run by a player.");
            return true;
        }
        Player player = (Player) sender;
        if (command.getName().equalsIgnoreCase("eventhub") || command.getName().equalsIgnoreCase("return")) {
            String worldName = command.getName().equalsIgnoreCase("eventhub") ? "eventhub" : "world";
            String consoleCommand = "mvtp " + player.getName() + " " + worldName;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), consoleCommand);
            player.sendMessage("Teleporting you to " + worldName + "...");
        } else if (command.getName().equalsIgnoreCase("togglescoreboard")) {
            toggleScoreboard(player);
        } else if (command.getName().equalsIgnoreCase("sendmoney")) {
            if (args.length != 2) {
            	String message = "Usage: /sendmoney <player> <amount>";
            	if (!digitalEconomyEnabled) message = "Digital economy is disabled, however the correct command usage would be: /sendmoney <player> <amount>";
                player.sendMessage(ChatColor.RED + message);
                return true;
            }
            Player targetPlayer = Bukkit.getPlayer(args[0]);
            if (targetPlayer == null) {
                player.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
            if (!digitalEconomyEnabled) {
            	player.sendMessage(ChatColor.RED + "Digital economy is disabled.");
            	return true;
            }
            try {
                double amount = Double.parseDouble(args[1]);
                if (amount <= 0) {
                    player.sendMessage(ChatColor.RED + "Amount must be greater than 0.");
                    return true;
                }
                // Check sender balance using Vault
                if (econ.getBalance(player) < amount) {
                    player.sendMessage(ChatColor.RED + "You don't have enough balance to send this amount.");
                    return true;
                }
                EconomyResponse withdrawResponse = econ.withdrawPlayer(player, amount);
                if (!withdrawResponse.transactionSuccess()) {
                    player.sendMessage(ChatColor.RED + "Transaction failed: " + withdrawResponse.errorMessage);
                    return true;
                }
                EconomyResponse depositResponse = econ.depositPlayer(targetPlayer, amount);
                if (!depositResponse.transactionSuccess()) {
                    // Refund sender if deposit fails.
                    econ.depositPlayer(player, amount);
                    player.sendMessage(ChatColor.RED + "Transaction failed: " + depositResponse.errorMessage);
                    return true;
                }
                player.sendMessage(ChatColor.GREEN + "You have successfully sent $" + amount + " to " + targetPlayer.getName() + ".");
                targetPlayer.sendMessage(ChatColor.GREEN + "You have received $" + amount + " from " + player.getName() + ".");
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid amount. Please enter a valid number.");
            }
        } else if (command.getName().equalsIgnoreCase("warn")) {
            if (!sender.hasPermission("serversideutils.warn")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /warn <player> <reason>");
                return true;
            }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
            UUID uuid = target.getUniqueId();
            String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            playerWarnings.computeIfAbsent(uuid, k -> new ArrayList<>()).add(reason);
            saveWarningsToFile();
            sender.sendMessage(ChatColor.YELLOW + "Warned " + target.getName() + " for: " + reason);
            target.sendMessage(ChatColor.RED + "You have been warned by an admin for: " + reason);
        } else if (command.getName().equalsIgnoreCase("getwarnings")) {
            if (args.length != 1) {
                sender.sendMessage(ChatColor.RED + "Usage: /getwarnings <player>");
                return true;
            }
            String playerName = args[0];
            OfflinePlayer target = null;
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.getName() != null && op.getName().equalsIgnoreCase(playerName)) {
                    target = op;
                    break;
                }
            }
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player " + playerName + " not found.");
                return true;
            }
            UUID uuid = target.getUniqueId();
            List<String> warnings = playerWarnings.get(uuid);
            if (warnings == null || warnings.isEmpty()) {
                sender.sendMessage(ChatColor.GREEN + target.getName() + " has no warnings.");
            } else {
                sender.sendMessage(ChatColor.GOLD + target.getName() + "'s Warnings:");
                for (int i = 0; i < warnings.size(); i++) {
                    sender.sendMessage(ChatColor.GRAY + String.valueOf(i + 1) + ". " + warnings.get(i));
                }
            }
        } else if (command.getName().equalsIgnoreCase("removewarning")) {
            if (!sender.hasPermission("serversideutils.removewarning")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
            if (args.length != 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /removewarning <player> <index>");
                return true;
            }
            OfflinePlayer target = null;
            String playerName = args[0];
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.getName() != null && op.getName().equalsIgnoreCase(playerName)) {
                    target = op;
                    break;
                }
            }
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
            UUID uuid = target.getUniqueId();
            List<String> warnings = playerWarnings.get(uuid);
            if (warnings == null || warnings.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "This player has no warnings.");
                return true;
            }
            try {
                int index = Integer.parseInt(args[1]) - 1;
                if (index < 0 || index >= warnings.size()) {
                    sender.sendMessage(ChatColor.RED + "Invalid warning number.");
                    return true;
                }
                String removed = warnings.remove(index);
                if (warnings.isEmpty())
                    playerWarnings.remove(uuid);
                saveWarningsToFile();
                sender.sendMessage(ChatColor.GREEN + "Removed warning: " + removed);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid number.");
            }
        } else if (command.getName().equalsIgnoreCase("clearwarnings")) {
            if (!sender.hasPermission("serversideutils.clearwarnings")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
            if (args.length != 1) {
                sender.sendMessage(ChatColor.RED + "Usage: /clearwarnings <player>");
                return true;
            }
            OfflinePlayer target = null;
            String playerName = args[0];
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.getName() != null && op.getName().equalsIgnoreCase(playerName)) {
                    target = op;
                    break;
                }
            }
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return true;
            }
            UUID uuid = target.getUniqueId();
            if (playerWarnings.remove(uuid) != null) {
                saveWarningsToFile();
                sender.sendMessage(ChatColor.GREEN + "Cleared all warnings for " + target.getName());
            } else {
                sender.sendMessage(ChatColor.YELLOW + "No warnings to clear.");
            }
        } else if (command.getName().equalsIgnoreCase("owarn")) {
            if (!sender.hasPermission("serversideutils.owarn")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /owarn <player> <reason>");
                return true;
            }
            String targetName = args[0];
            OfflinePlayer target = null;
            // Iterate through all offline players to find a matching name
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.getName() != null && op.getName().equalsIgnoreCase(targetName)) {
                    target = op;
                    break;
                }
            }
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Offline player not found.");
                return true;
            }
            UUID uuid = target.getUniqueId();
            String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            playerWarnings.computeIfAbsent(uuid, k -> new ArrayList<>()).add(reason);
            saveWarningsToFile();
            sender.sendMessage(ChatColor.GREEN + "Warned offline player " + target.getName() + " for: " + ChatColor.WHITE + reason);
        } else if (command.getName().equalsIgnoreCase("oclearwarnings")) {
            if (!sender.hasPermission("serversideutils.oclearwarnings")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
            if (args.length != 1) {
                sender.sendMessage(ChatColor.RED + "Usage: /oclearwarnings <player>");
                return true;
            }
            String targetName = args[0];
            OfflinePlayer target = null;
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.getName() != null && op.getName().equalsIgnoreCase(targetName)) {
                    target = op;
                    break;
                }
            }
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Offline player not found.");
                return true;
            }
            UUID uuid = target.getUniqueId();
            if (playerWarnings.remove(uuid) != null) {
                saveWarningsToFile();
                sender.sendMessage(ChatColor.GREEN + "Cleared all warnings for offline player " + target.getName());
            } else {
                sender.sendMessage(ChatColor.YELLOW + target.getName() + " has no warnings to clear.");
            }
        } else if (command.getName().equalsIgnoreCase("oremovewarning")) {
            if (!sender.hasPermission("serversideutils.oremovewarning")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
            if (args.length != 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /oremovewarning <player> <warning number>");
                return true;
            }
            String playerName = args[0];
            int warningIndex;
            try {
                warningIndex = Integer.parseInt(args[1]) - 1; // Convert to 0-based
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Warning number must be a valid integer.");
                return true;
            }
            OfflinePlayer target = null;
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.getName() != null && op.getName().equalsIgnoreCase(playerName)) {
                    target = op;
                    break;
                }
            }
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player " + playerName + " not found.");
                return true;
            }
            UUID uuid = target.getUniqueId();
            List<String> warnings = playerWarnings.get(uuid);
            if (warnings == null || warnings.isEmpty()) {
                sender.sendMessage(ChatColor.RED + target.getName() + " has no warnings.");
                return true;
            }
            if (warningIndex < 0 || warningIndex >= warnings.size()) {
                sender.sendMessage(ChatColor.RED + "Invalid warning number.");
                return true;
            }
            String removed = warnings.remove(warningIndex);
            if (warnings.isEmpty())
                playerWarnings.remove(uuid);
            saveWarningsToFile();
            sender.sendMessage(ChatColor.GREEN + "Removed warning from offline player: " + removed);
        } else if (command.getName().equalsIgnoreCase("sync")) {
    	    // This one's handled silently...
        	// Return true to fool the server & client into thinking it fully ran
    	    return true;
    	} else if (command.getName().equalsIgnoreCase("setcounter")) {
        	if (args.length != 1) {
        		sender.sendMessage("Usage: /setcounter <true|false>");
                return true;
        	}
        	if (!sender.hasPermission("serversideutils.setcounter")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
        	String arg = args[0].toLowerCase();
        	if (arg.equals("true")) {
        		trackingEnabled = true;
        		saveTracking(true);
                sender.sendMessage(ChatColor.GREEN + "Player count tracking enabled.");
        	} else if (arg.equals("false")) {
        		trackingEnabled = false;
        		saveTracking(false);
                sender.sendMessage(ChatColor.GREEN + "Player count tracking disabled.");
        	} else {
        		sender.sendMessage(ChatColor.RED + "Invalid argument. Use true or false.");
        	}
        	return true;
        } else if (command.getName().equalsIgnoreCase("lsrev")) {
        	if (args.length != 1) {
        		sender.sendMessage("Usage: /lsrev <true|false>");
                return true;
        	}
        	if (!sender.hasPermission("serversideutils.lsrev")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
                return true;
            }
        	String arg = args[0].toLowerCase();
        	if (arg.equals("true")) {
        		lifestealReviveEnabled = true;
        		saveLsRev(true);
                sender.sendMessage(ChatColor.GREEN + "LifeSteal revive system enabled.");
        	} else if (arg.equals("false")) {
        		lifestealReviveEnabled = false;
        		saveLsRev(false);
                sender.sendMessage(ChatColor.GREEN + "LifeSteal revive system disabled.");
        	} else {
        		sender.sendMessage(ChatColor.RED + "Invalid argument. Use true or false.");
        	}
        }
        return true;
    }
    @SuppressWarnings("unchecked")
    private void saveLifestealDict() {
        if (confFile == null) {
            confFile = new File(getDataFolder(), "conf.json");
        }

        if (!confFile.exists()) {
            confFile.getParentFile().mkdirs();
            saveResource("conf.json", false);
        }

        JSONObject trackState = new JSONObject();
        trackState.putAll(lifesteal);

        try (FileWriter dataWriter = new FileWriter(confFile)) {
            dataWriter.write(trackState.toJSONString());
        } catch (IOException e) {
            getLogger().severe("Failed to save configuration: " + e.getMessage());
        }
    }
    @SuppressWarnings("unchecked")
    private long loadSeed() {
        confFile = new File(getDataFolder(), "conf.json");

        if (!confFile.exists()) {
            getLogger().warning("Config file not found. Creating new config with seed = 0.");
            JSONObject defaultConfig = new JSONObject();
            defaultConfig.put("seed", 0L);

            try (FileWriter writer = new FileWriter(confFile)) {
                writer.write(defaultConfig.toJSONString());
            } catch (IOException e) {
                getLogger().severe("Failed to write default config: " + e.getMessage());
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
                getLogger().warning("Invalid or missing seed value in config. Defaulting to 0.");

                jsonObject.put("seed", 0L); // fix missing/invalid

                try (FileWriter writer = new FileWriter(confFile)) {
                    writer.write(jsonObject.toJSONString());
                } catch (IOException e) {
                    getLogger().severe("Failed to update config: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            getLogger().severe("Failed to load configuration: " + e.getMessage());
        }
        return 0L;
    }
    @SuppressWarnings("unchecked")
	private boolean loadLsRev() {
        confFile = new File(getDataFolder(), "conf.json");

        if (!confFile.exists()) {
            getLogger().warning("Config file not found. Creating new config with lsrev = false.");
            JSONObject defaultConfig = new JSONObject();
            defaultConfig.put("lsrev", false);

            try (FileWriter writer = new FileWriter(confFile)) {
                writer.write(defaultConfig.toJSONString());
            } catch (IOException e) {
                getLogger().severe("Failed to write default config: " + e.getMessage());
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
                getLogger().warning("Invalid or missing LifeSteal revive value in config. Defaulting to false.");

                jsonObject.put("lsrev", false); // fix bad/missing value

                try (FileWriter writer = new FileWriter(confFile)) {
                    writer.write(jsonObject.toJSONString());
                } catch (IOException e) {
                    getLogger().severe("Failed to update config: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            getLogger().severe("Failed to load configuration: " + e.getMessage());
        }

        return false;
    }
    @SuppressWarnings("unchecked")
	private boolean loadTracking() {
        confFile = new File(getDataFolder(), "conf.json");

        if (!confFile.exists()) {
            getLogger().warning("Config file not found. Creating new config with tracking = true.");

            JSONObject defaultConfig = new JSONObject();
            defaultConfig.put("tracking", true);

            try (FileWriter writer = new FileWriter(confFile)) {
                writer.write(defaultConfig.toJSONString());
            } catch (IOException e) {
                getLogger().severe("Failed to write default config: " + e.getMessage());
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
                getLogger().warning("Invalid tracking value in config. Defaulting to true.");
                jsonObject.put("tracking", true);
                try (FileWriter writer = new FileWriter(confFile)) {
                    writer.write(jsonObject.toJSONString());
                } catch (IOException e) {
                    getLogger().severe("Failed to update config: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            getLogger().severe("Failed to load configuration: " + e.getMessage());
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private void saveTracking(boolean value) {
    	confFile = new File(getDataFolder(), "conf.json");
        if (!confFile.exists()) {
            confFile.getParentFile().mkdirs();
            saveResource("conf.json", false);
        }
        JSONObject trackState = new JSONObject();
        trackState.put("tracking", value);
        try {
        	FileWriter dataWriter = new FileWriter(confFile);
        	dataWriter.write(trackState.toJSONString());
        	dataWriter.close();
        } catch (IOException e) {
        	getLogger().severe("Failed to save configuration: " + e.getMessage());
        }
    }
    private void saveLsRev(boolean value) {
        lifesteal.put("lsrev", value);
        saveLifestealDict();
    }
    private void saveSeed(long seed) {
        lifesteal.put("seed", seed);
        saveLifestealDict();
    }
    // Toggles the scoreboard on or off for a player.
    private void toggleScoreboard(Player player) {
        UUID uuid = player.getUniqueId();
        if (scoreboardPlayers.contains(uuid)) {
            // Turn off the scoreboard.
            player.setScoreboard(scoreboardManager.getNewScoreboard());
            scoreboardPlayers.remove(uuid);
            player.sendMessage(ChatColor.GREEN + "Status scoreboard hidden.");
        } else {
            // Turn on the scoreboard.
            Scoreboard board = buildScoreboard(player);
            player.setScoreboard(board);
            scoreboardPlayers.add(uuid);
            player.sendMessage(ChatColor.GREEN + "Status scoreboard displayed.");
        }
    }
    private void enableScoreboard(Player player) {
    	UUID uuid = player.getUniqueId();
    	if (!scoreboardPlayers.contains(uuid)) {
    		Scoreboard board = buildScoreboard(player);
            player.setScoreboard(board);
            scoreboardPlayers.add(uuid);
    	}
    }
    // Build a new scoreboard for the given player with updated balance.
    private Scoreboard buildScoreboard(Player player) {
        Scoreboard board = scoreboardManager.getNewScoreboard();
        Objective objective = board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, ChatColor.BOLD + "" + ChatColor.AQUA + "BakoSMP");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        
        // Top line: centered, bold, green "Status"
        String title = centerText(ChatColor.GREEN + "" + ChatColor.BOLD + "Your Status");
        Score titleScore = objective.getScore(title);
        titleScore.setScore(7);
        Score emptyScore = objective.getScore("");
        emptyScore.setScore(6);
        
        // Next line: player name in italic gold.
        String nameLine = ChatColor.GOLD + "" + ChatColor.ITALIC + player.getName();
        Score nameScore = objective.getScore(nameLine);
        nameScore.setScore(5);
        
        // Next line: balance text beneath the player name using Vault economy.
        double balance = econ.getBalance(player);
        String formattedBalance = String.format("%.2f", balance);
        String balanceLine = " ";
        if (digitalEconomyEnabled) balanceLine = ChatColor.WHITE + "Balance: " + ChatColor.GREEN + "$" + formattedBalance;
        Score balanceScore = objective.getScore(balanceLine);
        balanceScore.setScore(4);
        
        // Next line: Season text
        Score empty = objective.getScore(" ");
        empty.setScore(3);
        String seasonText = centerText(ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "SEASON " + Integer.toString(season));
        Score seasonScore = objective.getScore(seasonText);
        Score themeScore = objective.getScore(ChatColor.DARK_RED + "" + ChatColor.ITALIC + "Theme: " + theme);
        seasonScore.setScore(2);
        themeScore.setScore(1);
        return board;
    }
    
    // Listener for player join
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        GameMode mode = player.getGameMode();
        gameModeMap.put(playerUUID, mode);
        enableScoreboard(player);
        
        if (playerCredentials.containsKey(playerUUID)) {
            JSONObject creds = playerCredentials.get(playerUUID);
            String savedUsername = (String) creds.get("username");
            String encryptedPassword = (String) creds.get("password");
            String plainPassword = decryptPassword(encryptedPassword).trim().strip();
            
            // Move HTTP calls to async thread
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    // First check account status
                    if (!queryStatus(savedUsername, player)) {
                        return; // Player was kicked due to account moderation, ruh roh!
                    }

                    // Then verify credentials are still valid
                    if (queryLogin(savedUsername, plainPassword)) {
                        // Credentials are valid - do nothing, player can continue
                        return;
                    } else {
                        // Invalid credentials - remove them and freeze player on main thread
                        Bukkit.getScheduler().runTask(this, () -> {
                            playerCredentials.remove(playerUUID);
                            saveCredentialsToFile();
                            freezePlayer(player);
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    // On error, freeze the player as safety measure
                    Bukkit.getScheduler().runTask(this, () -> {
                        freezePlayer(player);
                    });
                }
            });
        } else {
            // No credentials saved - freeze immediately
            freezePlayer(player);
        }
    }
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        if (frozenPlayers.contains(p.getUniqueId())) {
            event.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You can't chat until you log in.");
        }
    }
    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player p = (Player) event.getEntity();
            if (frozenPlayers.contains(p.getUniqueId())) event.setCancelled(true);
        }
    }
    
    // Avoid leaking player creds to logs (shit will ensue if we don't)
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
    	String command = event.getMessage().toLowerCase();
    	String baseCommand = command.substring(1).split(" ")[0];
    	if (hiddenCommands.contains(baseCommand)) {
    		event.setCancelled(true);
    		handleHiddenCommand(event.getPlayer(), event.getMessage());
    	}
    }
    private void handleHiddenCommand(org.bukkit.entity.Player player, String command) {
        // Parse the command and arguments
        String[] parts = command.substring(1).split(" ");
        String baseCommand = parts[0];
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        
        switch (baseCommand.toLowerCase()) {
            case "sync":
                handleSyncCommand(player, args);
                break;
            case "secretcommand":
                player.sendMessage("Secret command executed silently!");
                break;
            case "adminpass":
                // Handle password command without logging
                break;
            default:
                player.sendMessage("Hidden command processed.");
                break;
        }
    }
    @SuppressWarnings("unchecked")
	private void handleSyncCommand(Player player, String[] args) {
    	if (args.length != 2) {
            player.sendMessage(ChatColor.RED + "Usage: /sync <username> <password>");
            return;
        }
    	if (!frozenPlayers.contains(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You are already authenticated.");
            return;
        }
    	String username = args[0];
        String password = args[1];
        if (queryLogin(username, password)) {
            // Save credentials UUID-based
            JSONObject playerCreds = new JSONObject();
            playerCreds.put("username", username);
            playerCreds.put("password", encryptPassword(password));
            playerCredentials.put(player.getUniqueId(), playerCreds);
            saveCredentialsToFile();

            // Unfreeze the player
            unfreezePlayer(player);
            player.sendMessage(ChatColor.GREEN + "Authentication successful! Your credentials have been saved.");
        } else {
            player.sendMessage(ChatColor.RED + "Authentication failed. Please check your credentials.");
        }
    }
    @EventHandler
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            Player p = (Player) event.getDamager();
            if (frozenPlayers.contains(p.getUniqueId())) {
                event.setCancelled(true);
                p.sendMessage(ChatColor.RED + "You can't attack until you log in.");
            }
        }
    }
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
    	Player p = event.getPlayer();
    	if (frozenPlayers.contains(p.getUniqueId())) event.setCancelled(true);
    }
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (frozenPlayers.contains(p.getUniqueId())) {
            event.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You can't break blocks until you log in.");
        }
    }
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (frozenPlayers.contains(p.getUniqueId())) {
            event.setCancelled(true);
            p.sendMessage(ChatColor.RED + "You can't place blocks until you log in.");
        }
    }
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
    	Bukkit.getScheduler().runTaskLater(this, this::savePlayerCount, 10L);
    }
    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        if (frozenPlayers.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }
    // Helper method to center text approximately (assuming 16 characters wide).
    private String centerText(String text) {
        String plain = ChatColor.stripColor(text);
        int totalLength = 16;
        // Warning: Here be dragons (and absolute pure bullshit)
        int padSize = (totalLength - plain.length()) / 2;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < padSize; i++) {
            sb.append(" ");
        }
        sb.append(text);
        return sb.toString();
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
    // Stinky weird LogFilter class
    private class CommandLogFilter implements Filter {
    	@Override
        public boolean isLoggable(LogRecord record) {
            String message = record.getMessage();
            
            if (message != null) {
                // Check for command execution patterns in logs
                if (message.contains("issued server command:") || 
                    message.contains("executed command:")) {
                    
                    for (String hiddenCmd : hiddenCommands) {
                        if (message.toLowerCase().contains("/" + hiddenCmd)) {
                            return false;
                        }
                    }
                }
            }
            return true;
    	}
    }
}
