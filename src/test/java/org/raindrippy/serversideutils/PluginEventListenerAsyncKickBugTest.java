package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.event.player.PlayerQuitEvent;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Isolated bug-pinning test for the async player-kick in the combat-log ban task.
 *
 * <p>Kept in its own class because triggering the illegal async kick poisons MockBukkit's
 * scheduler state; isolating it keeps the shared listener suite clean.
 */
class PluginEventListenerAsyncKickBugTest {

    @AfterEach
    void tearDown() {
        // The async-kick bug makes scheduler shutdown throw; swallow so state is released.
        try {
            if (MockBukkit.isMocked()) {
                MockBukkit.unmock();
            }
        } catch (Throwable ignored) {
            // expected while the bug is present
        }
    }

    @SuppressWarnings("unchecked")
    private static JSONObject creds(String username) {
        JSONObject o = new JSONObject();
        o.put("username", username);
        return o;
    }

    @Test
    @DisplayName("the 3-day ban task bans the account without kicking the player off an async thread")
    void banTaskDoesNotKickPlayerAsynchronously() {
        ServerMock server = MockBukkit.mock();
        Main plugin = mock(Main.class);
        lenient().when(plugin.isEnabled()).thenReturn(true);
        lenient().when(plugin.getName()).thenReturn("ServerUtils");
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("AsyncKickBugTest"));

        AuthService authService = mock(AuthService.class);
        CredentialsManager credentialsManager = mock(CredentialsManager.class);
        CryptoService cryptoService = mock(CryptoService.class);
        ApiClient apiClient = mock(ApiClient.class);
        ScoreboardService scoreboardService = mock(ScoreboardService.class);
        ConfigManager configManager = mock(ConfigManager.class);
        CombatManager combatManager = mock(CombatManager.class);
        CombatLogManager combatLogManager = mock(CombatLogManager.class);
        WarningsManager warningsManager = mock(WarningsManager.class);

        Map<UUID, List<String>> warningsStore = new HashMap<>();
        Map<UUID, JSONObject> credsStore = new HashMap<>();
        when(warningsManager.getWarnings()).thenReturn(warningsStore);
        when(credentialsManager.getCredentials()).thenReturn(credsStore);

        PluginEventListener listener = new PluginEventListener(plugin, authService, credentialsManager,
                cryptoService, apiClient, scoreboardService, configManager, combatManager,
                combatLogManager, warningsManager, new HashSet<>(List.of("sync")));

        // A REAL PlayerMock enforces Bukkit's main-thread-only kick rule.
        PlayerMock player = server.addPlayer("Repeat");
        UUID uuid = player.getUniqueId();
        when(combatManager.isInCombat(player)).thenReturn(true);
        when(combatLogManager.recordStrike(uuid)).thenReturn(3);
        credsStore.put(uuid, creds("bobaccount"));
        when(apiClient.banAccount("bobaccount")).thenReturn(true);

        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerQuit(event);

        // Running the ban task must not perform an asynchronous player kick (the kick is hopped
        // back onto the main thread), while the ban itself is still requested.
        assertDoesNotThrow(() -> {
            server.getScheduler().waitAsyncTasksFinished();
            server.getScheduler().performTicks(5);
        }, "ban task must not kick the player from an async thread");
        verify(apiClient).banAccount("bobaccount");
    }
}
