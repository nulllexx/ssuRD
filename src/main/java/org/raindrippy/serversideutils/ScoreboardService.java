package org.raindrippy.serversideutils;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ScoreboardService {
    private static final String OBJECTIVE_NAME = "balance";

    private final Set<UUID> scoreboardPlayers = new HashSet<>();
    private final ScoreboardManager scoreboardManager;
    private final Economy econ;
    private final boolean digitalEconomyEnabled;
    private final int season;
    private final String theme;

    public ScoreboardService(Economy econ, boolean digitalEconomyEnabled, int season, String theme) {
        this.econ = econ;
        this.digitalEconomyEnabled = digitalEconomyEnabled;
        this.season = season;
        this.theme = theme;
        this.scoreboardManager = Bukkit.getScoreboardManager();
    }

    public ScoreboardManager getManager() {
        return scoreboardManager;
    }

    public Set<UUID> getScoreboardPlayers() {
        return scoreboardPlayers;
    }

    public void toggle(Player player) {
        UUID uuid = player.getUniqueId();
        if (scoreboardPlayers.contains(uuid)) {
            player.setScoreboard(scoreboardManager.getNewScoreboard());
            scoreboardPlayers.remove(uuid);
            player.sendMessage(ChatColor.GREEN + "Status scoreboard hidden.");
        } else {
            Scoreboard board = build(player);
            player.setScoreboard(board);
            scoreboardPlayers.add(uuid);
            player.sendMessage(ChatColor.GREEN + "Status scoreboard displayed.");
        }
    }

    public void enable(Player player) {
        UUID uuid = player.getUniqueId();
        if (!scoreboardPlayers.contains(uuid)) {
            Scoreboard board = build(player);
            player.setScoreboard(board);
            scoreboardPlayers.add(uuid);
        }
    }

    public Scoreboard build(Player player) {
        Scoreboard board = scoreboardManager.getNewScoreboard();
        Objective objective = board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY,
                ChatColor.AQUA + "" + ChatColor.BOLD + "✦ BakoSMP ✦");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<String> lines = new ArrayList<>();

        lines.add(separator(0));

        lines.add(ChatColor.GOLD + "" + ChatColor.BOLD + " ✦ "
                + ChatColor.WHITE + ChatColor.ITALIC + player.getName());

        if (digitalEconomyEnabled) {
            String formattedBalance = String.format("%,.2f", econ.getBalance(player));
            lines.add(ChatColor.GRAY + " Balance: " + ChatColor.GREEN + "$" + formattedBalance);
        }

        lines.add(separator(1));

        lines.add(centerText(ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "SEASON " + season));
        lines.add(ChatColor.DARK_RED + " Theme: " + ChatColor.RED + "" + ChatColor.ITALIC + theme);

        lines.add(separator(2));

        // Higher scores render nearer the top, so count down from the top line.
        int score = lines.size();
        for (String line : lines) {
            objective.getScore(line).setScore(score--);
        }
        return board;
    }

    // simple separator line for scoreboard
    private String separator(int index) {
        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.DARK_GRAY).append(ChatColor.STRIKETHROUGH);
        for (int i = 0; i < 20; i++) {
            sb.append(" ");
        }
        for (int i = 0; i < index; i++) {
            sb.append(ChatColor.RESET);
        }
        return sb.toString();
    }

    private String centerText(String text) {
        String plain = ChatColor.stripColor(text);
        int totalLength = 20;
        int padSize = Math.max(0, (totalLength - plain.length()) / 2);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < padSize; i++) {
            sb.append(" ");
        }
        sb.append(text);
        return sb.toString();
    }
}
