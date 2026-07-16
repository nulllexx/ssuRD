package org.raindrippy.serversideutils;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CommandTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase();

        switch (cmd) {
            // No arguments — nothing to complete
            case "eventhub":
            case "return":
            case "togglescoreboard":
                return Collections.emptyList();

            // /<cmd> <player>
            case "getwarnings":
            case "clearwarnings":
            case "oclearwarnings":
                if (args.length == 1) {
                    return getAllPlayerNames(args[0]);
                }
                return Collections.emptyList();

            // /<cmd> <player> <reason>
            case "warn":
            case "owarn":
                if (args.length == 1) {
                    return getAllPlayerNames(args[0]);
                } else if (args.length == 2) {
                    return placeholder("<reason>");
                }
                return Collections.emptyList();

            // /<cmd> <player> <number>
            case "removewarning":
            case "oremovewarning":
                if (args.length == 1) {
                    return getAllPlayerNames(args[0]);
                } else if (args.length == 2) {
                    return placeholder("<number>");
                }
                return Collections.emptyList();

            // /sendmoney <player> <amount>
            case "sendmoney":
                if (args.length == 1) {
                    return getAllPlayerNames(args[0]);
                } else if (args.length == 2) {
                    return placeholder("<amount>", "100", "1000");
                }
                return Collections.emptyList();

            // /<cmd> <true|false>
            case "setcounter":
            case "lsrev":
                if (args.length == 1) {
                    return filterByPrefix(Arrays.asList("true", "false"), args[0]);
                }
                return Collections.emptyList();

            // /sync <username> <password>
            case "sync":
                if (args.length == 1) {
                    return placeholder("<username>");
                } else if (args.length == 2) {
                    return placeholder("<password>");
                }
                return Collections.emptyList();

            default:
                return Collections.emptyList();
        }
    }

    private List<String> placeholder(String... hints) {
        return Arrays.asList(hints);
    }

    private List<String> filterByPrefix(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }

    private List<String> getAllPlayerNames(String prefix) {
        List<String> names = new ArrayList<>();
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() != null && op.getName().toLowerCase().startsWith(prefix.toLowerCase())) {
                names.add(op.getName());
            }
        }
        return names;
    }
}
