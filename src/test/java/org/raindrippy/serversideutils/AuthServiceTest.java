package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.GameMode;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Tests for {@link AuthService}. Runs under MockBukkit so a real {@link PlayerMock} and the
 * Bukkit registries (GameMode, PotionEffectType) are available; collaborators are Mockito mocks.
 */
class AuthServiceTest {

    private ServerMock server;
    private ApiClient apiClient;
    private CredentialsManager credentialsManager;
    private CryptoService cryptoService;
    private Map<UUID, JSONObject> credsStore;
    private AuthService authService;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        apiClient = Mockito.mock(ApiClient.class);
        credentialsManager = Mockito.mock(CredentialsManager.class);
        cryptoService = new CryptoService("0123456789abcdef");
        credsStore = new HashMap<>();
        when(credentialsManager.getCredentials()).thenReturn(credsStore);
        authService = new AuthService(apiClient, credentialsManager, cryptoService);
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("freezePlayer marks the player frozen and puts them in spectator")
    void freezeMarksFrozen() {
        assertFalse(authService.isFrozen(player.getUniqueId()));
        authService.freezePlayer(player);
        assertTrue(authService.isFrozen(player.getUniqueId()));
        assertEquals(GameMode.SPECTATOR, player.getGameMode());
    }

    @Test
    @DisplayName("handleSync with wrong argument count shows usage and does not authenticate")
    void syncWrongArgCount() {
        authService.freezePlayer(player);
        authService.handleSync(player, new String[]{"onlyone"});
        verify(apiClient, never()).queryCredentials(Mockito.anyString(), Mockito.anyString());
        assertTrue(authService.isFrozen(player.getUniqueId()));
    }

    @Test
    @DisplayName("handleSync when already authenticated is rejected without an API call")
    void syncAlreadyAuthenticated() {
        // Player was never frozen -> treated as already authenticated.
        authService.handleSync(player, new String[]{"user", "pass"});
        verify(apiClient, never()).queryCredentials(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    @DisplayName("handleSync success saves encrypted credentials and unfreezes")
    void syncSuccess() {
        UUID uuid = player.getUniqueId();
        // Simulate the join flow having recorded the player's gamemode before freezing.
        authService.getGameModeMap().put(uuid, GameMode.SURVIVAL);
        authService.freezePlayer(player);
        when(apiClient.queryCredentials("user", "pass")).thenReturn(ApiClient.LoginResult.SUCCESS);

        authService.handleSync(player, new String[]{"user", "pass"});

        assertFalse(authService.isFrozen(uuid), "player should be unfrozen after success");
        JSONObject creds = credsStore.get(uuid);
        assertEquals("user", creds.get("username"));
        // Password must be stored encrypted, never in plaintext.
        assertNotEquals("pass", creds.get("password"));
        assertEquals("pass", cryptoService.decrypt((String) creds.get("password")));
        verify(credentialsManager).save();
    }

    @Test
    @DisplayName("handleSync failure does not store credentials and keeps the player frozen")
    void syncFailure() {
        UUID uuid = player.getUniqueId();
        authService.freezePlayer(player);
        when(apiClient.queryCredentials("user", "wrong")).thenReturn(ApiClient.LoginResult.INVALID_CREDENTIALS);

        authService.handleSync(player, new String[]{"user", "wrong"});

        assertTrue(authService.isFrozen(uuid));
        assertFalse(credsStore.containsKey(uuid));
        verify(credentialsManager, never()).save();
    }

    @Test
    @DisplayName("unfreezePlayer handles a missing recorded gamemode without throwing")
    void unfreezeWithoutRecordedGameMode() {
        authService.freezePlayer(player);
        // gameModeMap has no entry for this player; unfreeze must default the gamemode, not NPE.
        assertDoesNotThrow(() -> authService.unfreezePlayer(player));
        assertEquals(GameMode.SURVIVAL, player.getGameMode());
    }
}
