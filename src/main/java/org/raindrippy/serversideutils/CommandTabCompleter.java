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
import java.util.stream.Collectors;

public class CommandTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("warn") || cmd.equals("owarn") || cmd.equals("getwarnings")
                || cmd.equals("clearwarnings") || cmd.equals("oclearwarnings")) {
            if (args.length == 1) {
                return getAllPlayerNames(args[0]);
            }
        } else if (cmd.equals("removewarning") || cmd.equals("oremovewarning")) {
            if (args.length == 1) {
                return getAllPlayerNames(args[0]);
            } else if (args.length == 2) {
                return Collections.singletonList("<number>");
            }
        } else if (cmd.equals("setcounter") || cmd.equals("lsrev")) {
            if (args.length == 1) {
                return Arrays.asList("true", "false").stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
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
