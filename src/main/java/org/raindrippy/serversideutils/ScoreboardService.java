package org.raindrippy.serversideutils;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.util.HashSet;
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
                ChatColor.BOLD + "" + ChatColor.AQUA + "BakoSMP");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        String title = centerText(ChatColor.GREEN + "" + ChatColor.BOLD + "Your Status");
        Score titleScore = objective.getScore(title);
        titleScore.setScore(7);
        Score emptyScore = objective.getScore("");
        emptyScore.setScore(6);

        String nameLine = ChatColor.GOLD + "" + ChatColor.ITALIC + player.getName();
        Score nameScore = objective.getScore(nameLine);
        nameScore.setScore(5);

        double balance = econ.getBalance(player);
        String formattedBalance = String.format("%.2f", balance);
        String balanceLine = " ";
        if (digitalEconomyEnabled) {
            balanceLine = ChatColor.WHITE + "Balance: " + ChatColor.GREEN + "$" + formattedBalance;
        }
        Score balanceScore = objective.getScore(balanceLine);
        balanceScore.setScore(4);

        Score empty = objective.getScore(" ");
        empty.setScore(3);
        String seasonText = centerText(ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "SEASON " + Integer.toString(season));
        Score seasonScore = objective.getScore(seasonText);
        Score themeScore = objective.getScore(ChatColor.DARK_RED + "" + ChatColor.ITALIC + "Theme: " + theme);
        seasonScore.setScore(2);
        themeScore.setScore(1);
        return board;
    }

    private String centerText(String text) {
        String plain = ChatColor.stripColor(text);
        int totalLength = 16;
        int padSize = (totalLength - plain.length()) / 2;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < padSize; i++) {
            sb.append(" ");
        }
        sb.append(text);
        return sb.toString();
    }
}
