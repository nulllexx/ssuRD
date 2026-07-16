package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** Tests for {@link CommandTabCompleter}. */
class CommandTabCompleterTest {

    private ServerMock server;
    private final CommandTabCompleter completer = new CommandTabCompleter();
    private CommandSender sender;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        sender = mock(CommandSender.class);
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

    private List<String> complete(String cmd, String... args) {
        return completer.onTabComplete(sender, command(cmd), cmd, args);
    }

    @Test
    @DisplayName("setcounter first arg completes true/false with a prefix filter")
    void setCounterBooleanFilter() {
        assertEquals(List.of("true", "false"), complete("setcounter", ""));
        assertEquals(List.of("true"), complete("setcounter", "t"));
        assertEquals(List.of("false"), complete("lsrev", "f"));
    }

    @Test
    @DisplayName("removewarning second arg suggests the <number> placeholder")
    void removeWarningNumberPlaceholder() {
        assertEquals(List.of("<number>"), complete("removewarning", "Bob", ""));
    }

    @Test
    @DisplayName("warn first arg completes online/offline player names by prefix, case-insensitively")
    void warnPlayerNameCompletion() {
        server.addPlayer("Alice");
        server.addPlayer("Alan");
        server.addPlayer("Bob");
        List<String> result = complete("warn", "al");
        assertTrue(result.contains("Alice"), "expected Alice in " + result);
        assertTrue(result.contains("Alan"), "expected Alan in " + result);
        assertTrue(!result.contains("Bob"), "Bob should be filtered out");
    }

    @Test
    @DisplayName("warn second arg suggests a <reason> placeholder")
    void warnReasonPlaceholder() {
        assertEquals(List.of("<reason>"), complete("warn", "Bob", ""));
        assertEquals(List.of("<reason>"), complete("owarn", "Bob", ""));
    }

    @Test
    @DisplayName("sendmoney completes players then an <amount> placeholder with examples")
    void sendMoneyCompletion() {
        server.addPlayer("Alice");
        assertTrue(complete("sendmoney", "al").contains("Alice"));
        assertEquals(List.of("<amount>", "100", "1000"), complete("sendmoney", "Alice", ""));
    }

    @Test
    @DisplayName("sync suggests <username> then <password> placeholders")
    void syncPlaceholders() {
        assertEquals(List.of("<username>"), complete("sync", ""));
        assertEquals(List.of("<password>"), complete("sync", "user", ""));
    }

    @Test
    @DisplayName("no-argument commands return no completions")
    void noArgCommandsEmpty() {
        assertTrue(complete("eventhub", "").isEmpty());
        assertTrue(complete("return", "").isEmpty());
        assertTrue(complete("togglescoreboard", "").isEmpty());
    }

    @Test
    @DisplayName("oremovewarning second arg suggests the <number> placeholder")
    void oremoveWarningNumberPlaceholder() {
        assertEquals(List.of("<number>"), complete("oremovewarning", "Bob", ""));
    }

    @Test
    @DisplayName("an unrecognized command returns no completions")
    void unknownCommandEmpty() {
        assertTrue(complete("flyaway", "x").isEmpty());
    }
}
