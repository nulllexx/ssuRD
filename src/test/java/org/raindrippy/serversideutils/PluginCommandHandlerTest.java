package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.OfflinePlayer;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Tests for {@link PluginCommandHandler} command validation, economy transfer, and warnings. */
class PluginCommandHandlerTest {

    private ServerMock server;
    private PluginMock plugin;
    private WarningsManager warningsManager;
    private Map<UUID, List<String>> warningsStore;
    private ScoreboardService scoreboardService;
    private ConfigManager configManager;
    private Economy econ;
    private PluginCommandHandler handler;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        warningsManager = mock(WarningsManager.class);
        warningsStore = new HashMap<>();
        when(warningsManager.getWarnings()).thenReturn(warningsStore);
        scoreboardService = mock(ScoreboardService.class);
        configManager = mock(ConfigManager.class);
        econ = mock(Economy.class);
        handler = new PluginCommandHandler(warningsManager, scoreboardService, configManager, econ, true);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static Command command(String name) {
        Command c = mock(Command.class);
        when(c.getName()).thenReturn(name);
        return c;
    }

    private static EconomyResponse ok(double amount) {
        return new EconomyResponse(amount, 0, ResponseType.SUCCESS, null);
    }

    private static EconomyResponse fail() {
        return new EconomyResponse(0, 0, ResponseType.FAILURE, "boom");
    }

    // ----- generic dispatch -----

    @Test
    @DisplayName("a non-player sender is rejected")
    void nonPlayerRejected() {
        CommandSender console = mock(CommandSender.class);
        boolean handled = handler.onCommand(console, command("warn"), "warn", new String[]{"Bob", "spam"});
        assertTrue(handled);
        verify(console).sendMessage(Mockito.contains("only be run by a player"));
    }

    // ----- /sendmoney -----

    @Test
    @DisplayName("/sendmoney with wrong arg count shows usage and moves no money")
    void sendMoneyWrongArgs() {
        PlayerMock alice = server.addPlayer("Alice");
        handler.onCommand(alice, command("sendmoney"), "sendmoney", new String[]{"Bob"});
        assertTrue(alice.nextMessage().contains("Usage"));
        verify(econ, never()).withdrawPlayer(Mockito.any(OfflinePlayer.class), anyDouble());
    }

    @Test
    @DisplayName("/sendmoney with a non-numeric amount is rejected")
    void sendMoneyNaN() {
        PlayerMock alice = server.addPlayer("Alice");
        server.addPlayer("Bob");
        handler.onCommand(alice, command("sendmoney"), "sendmoney", new String[]{"Bob", "abc"});
        assertTrue(drainFor(alice, "Invalid amount"));
        verify(econ, never()).withdrawPlayer(Mockito.any(OfflinePlayer.class), anyDouble());
    }

    @Test
    @DisplayName("/sendmoney with a non-positive amount is rejected")
    void sendMoneyNonPositive() {
        PlayerMock alice = server.addPlayer("Alice");
        server.addPlayer("Bob");
        when(econ.getBalance(alice)).thenReturn(1000.0);
        handler.onCommand(alice, command("sendmoney"), "sendmoney", new String[]{"Bob", "0"});
        assertTrue(drainFor(alice, "greater than 0"));
        verify(econ, never()).withdrawPlayer(Mockito.any(OfflinePlayer.class), anyDouble());
    }

    @Test
    @DisplayName("/sendmoney with insufficient balance is rejected")
    void sendMoneyInsufficient() {
        PlayerMock alice = server.addPlayer("Alice");
        server.addPlayer("Bob");
        when(econ.getBalance(alice)).thenReturn(5.0);
        handler.onCommand(alice, command("sendmoney"), "sendmoney", new String[]{"Bob", "10"});
        assertTrue(drainFor(alice, "enough balance"));
        verify(econ, never()).withdrawPlayer(Mockito.any(OfflinePlayer.class), anyDouble());
    }

    @Test
    @DisplayName("/sendmoney refunds the sender when the deposit to the target fails")
    void sendMoneyDepositFailureRollsBack() {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        when(econ.getBalance(alice)).thenReturn(100.0);
        when(econ.withdrawPlayer((OfflinePlayer) alice, 10.0)).thenReturn(ok(10.0));
        when(econ.depositPlayer(bob, 10.0)).thenReturn(fail());

        handler.onCommand(alice, command("sendmoney"), "sendmoney", new String[]{"Bob", "10"});

        // Rollback: the withdrawn amount is deposited back to the sender.
        verify(econ).withdrawPlayer(alice, 10.0);
        verify(econ).depositPlayer(bob, 10.0);
        verify(econ).depositPlayer(alice, 10.0);
        assertTrue(drainFor(alice, "Transaction failed"));
    }

    @Test
    @DisplayName("/sendmoney success withdraws, deposits, and notifies both players")
    void sendMoneySuccess() {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        when(econ.getBalance(alice)).thenReturn(100.0);
        when(econ.withdrawPlayer((OfflinePlayer) alice, 10.0)).thenReturn(ok(10.0));
        when(econ.depositPlayer(bob, 10.0)).thenReturn(ok(10.0));

        handler.onCommand(alice, command("sendmoney"), "sendmoney", new String[]{"Bob", "10"});

        verify(econ).withdrawPlayer(alice, 10.0);
        verify(econ).depositPlayer(bob, 10.0);
        verify(econ, never()).depositPlayer(eq(alice), anyDouble()); // no rollback on success
        assertTrue(drainFor(alice, "successfully sent"));
        assertTrue(drainFor(bob, "received"));
    }

    // ----- /warn -----

    @Test
    @DisplayName("/warn without permission is denied and files no warning")
    void warnNoPermission() {
        PlayerMock alice = server.addPlayer("Alice");
        server.addPlayer("Bob");
        handler.onCommand(alice, command("warn"), "warn", new String[]{"Bob", "spam"});
        assertTrue(drainFor(alice, "permission"));
        verify(warningsManager, never()).save();
    }

    @Test
    @DisplayName("/warn with permission but unknown target reports not found")
    void warnTargetNotFound() {
        PlayerMock alice = server.addPlayer("Alice");
        alice.addAttachment(plugin, "serversideutils.warn", true);
        handler.onCommand(alice, command("warn"), "warn", new String[]{"Ghost", "spam"});
        assertTrue(drainFor(alice, "Player not found"));
        verify(warningsManager, never()).save();
    }

    @Test
    @DisplayName("/warn files the joined reason against the target and saves")
    void warnFilesReason() {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        alice.addAttachment(plugin, "serversideutils.warn", true);

        handler.onCommand(alice, command("warn"), "warn", new String[]{"Bob", "spamming", "the", "chat"});

        List<String> bobWarnings = warningsStore.get(bob.getUniqueId());
        assertEquals(1, bobWarnings.size());
        assertEquals("spamming the chat", bobWarnings.get(0));
        verify(warningsManager).save();
    }

    // ----- /removewarning -----

    @Test
    @DisplayName("/removewarning with an out-of-range index is rejected and keeps the warning")
    void removeWarningOutOfRange() {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        alice.addAttachment(plugin, "serversideutils.removewarning", true);
        warningsStore.put(bob.getUniqueId(), new ArrayList<>(List.of("only one")));

        handler.onCommand(alice, command("removewarning"), "removewarning", new String[]{"Bob", "5"});

        assertTrue(drainFor(alice, "Invalid warning number"));
        assertEquals(1, warningsStore.get(bob.getUniqueId()).size());
    }

    @Test
    @DisplayName("/removewarning removes the entry and drops the UUID key when the list empties")
    void removeWarningEmptiesKey() {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        alice.addAttachment(plugin, "serversideutils.removewarning", true);
        warningsStore.put(bob.getUniqueId(), new ArrayList<>(List.of("only one")));

        handler.onCommand(alice, command("removewarning"), "removewarning", new String[]{"Bob", "1"});

        assertTrue(drainFor(alice, "Removed warning"));
        assertFalse(warningsStore.containsKey(bob.getUniqueId()),
                "empty warning list should remove the player's key");
        verify(warningsManager).save();
    }

    // ----- /setcounter -----

    @Test
    @DisplayName("/setcounter true enables tracking via ConfigManager")
    void setCounterTrue() {
        PlayerMock alice = server.addPlayer("Alice");
        alice.addAttachment(plugin, "serversideutils.setcounter", true);
        handler.onCommand(alice, command("setcounter"), "setcounter", new String[]{"true"});
        verify(configManager).saveTracking(true);
    }

    @Test
    @DisplayName("/setcounter false disables tracking via ConfigManager")
    void setCounterFalse() {
        PlayerMock alice = server.addPlayer("Alice");
        alice.addAttachment(plugin, "serversideutils.setcounter", true);
        handler.onCommand(alice, command("setcounter"), "setcounter", new String[]{"false"});
        verify(configManager).saveTracking(false);
    }

    @Test
    @DisplayName("/setcounter with an invalid argument does not touch ConfigManager")
    void setCounterInvalid() {
        PlayerMock alice = server.addPlayer("Alice");
        alice.addAttachment(plugin, "serversideutils.setcounter", true);
        handler.onCommand(alice, command("setcounter"), "setcounter", new String[]{"maybe"});
        verify(configManager, never()).saveTracking(Mockito.anyBoolean());
        assertTrue(drainFor(alice, "Invalid argument"));
    }

    /** Drains queued messages for a player until one contains {@code needle}. */
    private static boolean drainFor(PlayerMock player, String needle) {
        String msg;
        while ((msg = player.nextMessage()) != null) {
            if (msg.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
