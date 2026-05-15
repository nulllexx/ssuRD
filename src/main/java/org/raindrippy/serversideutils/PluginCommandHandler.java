package org.raindrippy.serversideutils;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PluginCommandHandler implements CommandExecutor {
    private final WarningsManager warningsManager;
    private final ScoreboardService scoreboardService;
    private final ConfigManager configManager;
    private final Economy econ;
    private final boolean digitalEconomyEnabled;

    public PluginCommandHandler(WarningsManager warningsManager,
                                ScoreboardService scoreboardService,
                                ConfigManager configManager,
                                Economy econ,
                                boolean digitalEconomyEnabled) {
        this.warningsManager = warningsManager;
        this.scoreboardService = scoreboardService;
        this.configManager = configManager;
        this.econ = econ;
        this.digitalEconomyEnabled = digitalEconomyEnabled;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be run by a player.");
            return true;
        }
        Player player = (Player) sender;
        String name = command.getName().toLowerCase();

        switch (name) {
            case "eventhub":
            case "return":
                return handleTeleport(player, name);
            case "togglescoreboard":
                scoreboardService.toggle(player);
                return true;
            case "sendmoney":
                return handleSendMoney(player, args);
            case "warn":
                return handleWarn(sender, args);
            case "getwarnings":
                return handleGetWarnings(sender, args);
            case "removewarning":
                return handleRemoveWarning(sender, args);
            case "clearwarnings":
                return handleClearWarnings(sender, args);
            case "owarn":
                return handleOwarn(sender, args);
            case "oclearwarnings":
                return handleOclearWarnings(sender, args);
            case "oremovewarning":
                return handleOremoveWarning(sender, args);
            case "sync":
                // Handled silently in event listener
                return true;
            case "setcounter":
                return handleSetCounter(sender, args);
            case "lsrev":
                return handleLsRev(sender, args);
            default:
                return true;
        }
    }

    private boolean handleTeleport(Player player, String cmdName) {
        String worldName = cmdName.equals("eventhub") ? "eventhub" : "world";
        String consoleCommand = "mvtp " + player.getName() + " " + worldName;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), consoleCommand);
        player.sendMessage("Teleporting you to " + worldName + "...");
        return true;
    }

    private boolean handleSendMoney(Player player, String[] args) {
        if (args.length != 2) {
            String message = "Usage: /sendmoney <player> <amount>";
            if (!digitalEconomyEnabled) {
                message = "Digital economy is disabled, however the correct command usage would be: /sendmoney <player> <amount>";
            }
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
                econ.depositPlayer(player, amount);
                player.sendMessage(ChatColor.RED + "Transaction failed: " + depositResponse.errorMessage);
                return true;
            }
            player.sendMessage(ChatColor.GREEN + "You have successfully sent $" + amount + " to " + targetPlayer.getName() + ".");
            targetPlayer.sendMessage(ChatColor.GREEN + "You have received $" + amount + " from " + player.getName() + ".");
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Invalid amount. Please enter a valid number.");
        }
        return true;
    }

    private boolean handleWarn(CommandSender sender, String[] args) {
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
        Map<UUID, List<String>> warnings = warningsManager.getWarnings();
        warnings.computeIfAbsent(uuid, k -> new ArrayList<>()).add(reason);
        warningsManager.save();
        sender.sendMessage(ChatColor.YELLOW + "Warned " + target.getName() + " for: " + reason);
        target.sendMessage(ChatColor.RED + "You have been warned by an admin for: " + reason);
        return true;
    }

    private boolean handleGetWarnings(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /getwarnings <player>");
            return true;
        }
        OfflinePlayer target = findOfflinePlayer(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player " + args[0] + " not found.");
            return true;
        }
        UUID uuid = target.getUniqueId();
        List<String> warnings = warningsManager.getWarnings().get(uuid);
        if (warnings == null || warnings.isEmpty()) {
            sender.sendMessage(ChatColor.GREEN + target.getName() + " has no warnings.");
        } else {
            sender.sendMessage(ChatColor.GOLD + target.getName() + "'s Warnings:");
            for (int i = 0; i < warnings.size(); i++) {
                sender.sendMessage(ChatColor.GRAY + String.valueOf(i + 1) + ". " + warnings.get(i));
            }
        }
        return true;
    }

    private boolean handleRemoveWarning(CommandSender sender, String[] args) {
        if (!sender.hasPermission("serversideutils.removewarning")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /removewarning <player> <index>");
            return true;
        }
        OfflinePlayer target = findOfflinePlayer(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found.");
            return true;
        }
        UUID uuid = target.getUniqueId();
        Map<UUID, List<String>> warningsMap = warningsManager.getWarnings();
        List<String> warnings = warningsMap.get(uuid);
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
            if (warnings.isEmpty()) warningsMap.remove(uuid);
            warningsManager.save();
            sender.sendMessage(ChatColor.GREEN + "Removed warning: " + removed);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid number.");
        }
        return true;
    }

    private boolean handleClearWarnings(CommandSender sender, String[] args) {
        if (!sender.hasPermission("serversideutils.clearwarnings")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /clearwarnings <player>");
            return true;
        }
        OfflinePlayer target = findOfflinePlayer(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found.");
            return true;
        }
        UUID uuid = target.getUniqueId();
        if (warningsManager.getWarnings().remove(uuid) != null) {
            warningsManager.save();
            sender.sendMessage(ChatColor.GREEN + "Cleared all warnings for " + target.getName());
        } else {
            sender.sendMessage(ChatColor.YELLOW + "No warnings to clear.");
        }
        return true;
    }

    private boolean handleOwarn(CommandSender sender, String[] args) {
        if (!sender.hasPermission("serversideutils.owarn")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /owarn <player> <reason>");
            return true;
        }
        OfflinePlayer target = findOfflinePlayer(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Offline player not found.");
            return true;
        }
        UUID uuid = target.getUniqueId();
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Map<UUID, List<String>> warnings = warningsManager.getWarnings();
        warnings.computeIfAbsent(uuid, k -> new ArrayList<>()).add(reason);
        warningsManager.save();
        sender.sendMessage(ChatColor.GREEN + "Warned offline player " + target.getName() + " for: " + ChatColor.WHITE + reason);
        return true;
    }

    private boolean handleOclearWarnings(CommandSender sender, String[] args) {
        if (!sender.hasPermission("serversideutils.oclearwarnings")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /oclearwarnings <player>");
            return true;
        }
        OfflinePlayer target = findOfflinePlayer(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Offline player not found.");
            return true;
        }
        UUID uuid = target.getUniqueId();
        if (warningsManager.getWarnings().remove(uuid) != null) {
            warningsManager.save();
            sender.sendMessage(ChatColor.GREEN + "Cleared all warnings for offline player " + target.getName());
        } else {
            sender.sendMessage(ChatColor.YELLOW + target.getName() + " has no warnings to clear.");
        }
        return true;
    }

    private boolean handleOremoveWarning(CommandSender sender, String[] args) {
        if (!sender.hasPermission("serversideutils.oremovewarning")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /oremovewarning <player> <warning number>");
            return true;
        }
        int warningIndex;
        try {
            warningIndex = Integer.parseInt(args[1]) - 1;
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Warning number must be a valid integer.");
            return true;
        }
        OfflinePlayer target = findOfflinePlayer(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player " + args[0] + " not found.");
            return true;
        }
        UUID uuid = target.getUniqueId();
        Map<UUID, List<String>> warningsMap = warningsManager.getWarnings();
        List<String> warnings = warningsMap.get(uuid);
        if (warnings == null || warnings.isEmpty()) {
            sender.sendMessage(ChatColor.RED + target.getName() + " has no warnings.");
            return true;
        }
        if (warningIndex < 0 || warningIndex >= warnings.size()) {
            sender.sendMessage(ChatColor.RED + "Invalid warning number.");
            return true;
        }
        String removed = warnings.remove(warningIndex);
        if (warnings.isEmpty()) warningsMap.remove(uuid);
        warningsManager.save();
        sender.sendMessage(ChatColor.GREEN + "Removed warning from offline player: " + removed);
        return true;
    }

    private boolean handleSetCounter(CommandSender sender, String[] args) {
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
            configManager.saveTracking(true);
            sender.sendMessage(ChatColor.GREEN + "Player count tracking enabled.");
        } else if (arg.equals("false")) {
            configManager.saveTracking(false);
            sender.sendMessage(ChatColor.GREEN + "Player count tracking disabled.");
        } else {
            sender.sendMessage(ChatColor.RED + "Invalid argument. Use true or false.");
        }
        return true;
    }

    private boolean handleLsRev(CommandSender sender, String[] args) {
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
            configManager.saveLsRev(true);
            sender.sendMessage(ChatColor.GREEN + "LifeSteal revive system enabled.");
        } else if (arg.equals("false")) {
            configManager.saveLsRev(false);
            sender.sendMessage(ChatColor.GREEN + "LifeSteal revive system disabled.");
        } else {
            sender.sendMessage(ChatColor.RED + "Invalid argument. Use true or false.");
        }
        return true;
    }

    private OfflinePlayer findOfflinePlayer(String name) {
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().equalsIgnoreCase(name)) {
                return op;
            }
        }
        return null;
    }
}
