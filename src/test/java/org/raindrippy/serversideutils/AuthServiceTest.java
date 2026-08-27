package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.potion.PotionEffectType;
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
    @DisplayName("freezePlayer marks the player frozen and puts them in adventure mode")
    void freezeMarksFrozen() {
        assertFalse(authService.isFrozen(player.getUniqueId()));
        authService.freezePlayer(player);
        assertTrue(authService.isFrozen(player.getUniqueId()));
        // Adventure, not spectator: a spectator flies through the waiting chamber's walls and can
        // use the spectate menu to teleport to a real player.
        assertEquals(GameMode.ADVENTURE, player.getGameMode());
    }

    @Test
    @DisplayName("a frozen player can see and move: no blindness, normal walk speed")
    void freezeLeavesPlayerMobile() {
        authService.freezePlayer(player);
        assertFalse(player.hasPotionEffect(PotionEffectType.BLINDNESS), "the chamber must be visible");
        assertEquals(0.2f, player.getWalkSpeed(), "the player must be able to walk around it");
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
    @DisplayName("handleSync success remembers the network the player synced from")
    void syncRemembersNetwork() throws Exception {
        UUID uuid = player.getUniqueId();
        player.setAddress(new InetSocketAddress(InetAddress.getByName("1.2.3.4"), 25565));
        authService.freezePlayer(player);
        when(apiClient.queryCredentials("user", "pass")).thenReturn(ApiClient.LoginResult.SUCCESS);

        authService.handleSync(player, new String[]{"user", "pass"});

        NetworkGuard guard = new NetworkGuard(cryptoService);
        JSONObject creds = credsStore.get(uuid);
        assertTrue(guard.isKnown(creds, guard.fingerprint(player)));
    }

    @Test
    @DisplayName("re-syncing from a second network keeps the first one remembered")
    void resyncKeepsEarlierNetwork() throws Exception {
        UUID uuid = player.getUniqueId();
        NetworkGuard guard = new NetworkGuard(cryptoService);
        when(apiClient.queryCredentials("user", "pass")).thenReturn(ApiClient.LoginResult.SUCCESS);

        player.setAddress(new InetSocketAddress(InetAddress.getByName("1.2.3.4"), 25565));
        authService.freezePlayer(player);
        authService.handleSync(player, new String[]{"user", "pass"});
        String homeNetwork = guard.fingerprint(player);

        // Same player, different network: the join path would have re-frozen them.
        player.setAddress(new InetSocketAddress(InetAddress.getByName("9.9.9.9"), 25565));
        authService.freezePlayer(player);
        authService.handleSync(player, new String[]{"user", "pass"});

        JSONObject creds = credsStore.get(uuid);
        assertTrue(guard.isKnown(creds, homeNetwork), "the original network must survive a re-sync");
        assertTrue(guard.isKnown(creds, guard.fingerprint(player)));
    }

    // ---------- account binding ----------

    @Test
    @DisplayName("a character bound to one account rejects a sync with a different account")
    void syncRejectsDifferentAccount() {
        UUID uuid = player.getUniqueId();
        // Character already linked to 'alice'.
        authService.freezePlayer(player);
        when(apiClient.queryCredentials("alice", "pw")).thenReturn(ApiClient.LoginResult.SUCCESS);
        authService.handleSync(player, new String[]{"alice", "pw"});

        // Someone else on this Minecraft account tries their own, perfectly valid, RainDrippy login.
        authService.freezePlayer(player);
        Mockito.lenient().when(apiClient.queryCredentials("mallory", "theirpw"))
                .thenReturn(ApiClient.LoginResult.SUCCESS);

        authService.handleSync(player, new String[]{"mallory", "theirpw"});

        assertTrue(authService.isFrozen(uuid), "the takeover attempt must not authenticate");
        assertEquals("alice", credsStore.get(uuid).get("username"), "binding must be unchanged");
        // Rejected before the API is consulted, so the server is not a credential-checking oracle.
        verify(apiClient, never()).queryCredentials("mallory", "theirpw");
    }

    @Test
    @DisplayName("the bound account can always re-sync, regardless of capitalisation")
    void syncAcceptsSameAccountAnyCase() {
        UUID uuid = player.getUniqueId();
        authService.freezePlayer(player);
        when(apiClient.queryCredentials("alice", "pw")).thenReturn(ApiClient.LoginResult.SUCCESS);
        authService.handleSync(player, new String[]{"alice", "pw"});

        authService.freezePlayer(player);
        when(apiClient.queryCredentials("Alice", "pw")).thenReturn(ApiClient.LoginResult.SUCCESS);

        authService.handleSync(player, new String[]{"Alice", "pw"});

        assertFalse(authService.isFrozen(uuid), "the real owner must not be locked out by case");
    }

    @Test
    @DisplayName("an unbound character may be claimed by any valid account")
    void firstSyncBindsFreely() {
        UUID uuid = player.getUniqueId();
        authService.freezePlayer(player);
        when(apiClient.queryCredentials("newcomer", "pw")).thenReturn(ApiClient.LoginResult.SUCCESS);

        authService.handleSync(player, new String[]{"newcomer", "pw"});

        assertFalse(authService.isFrozen(uuid));
        assertEquals("newcomer", credsStore.get(uuid).get("username"));
    }

    @Test
    @DisplayName("a stale password does not unbind the character")
    void staledPasswordKeepsBinding() {
        UUID uuid = player.getUniqueId();
        authService.freezePlayer(player);
        when(apiClient.queryCredentials("alice", "pw")).thenReturn(ApiClient.LoginResult.SUCCESS);
        authService.handleSync(player, new String[]{"alice", "pw"});

        // The join path drops only the secret when the stored password stops working.
        credsStore.get(uuid).remove("password");
        authService.freezePlayer(player);
        Mockito.lenient().when(apiClient.queryCredentials("mallory", "theirpw"))
                .thenReturn(ApiClient.LoginResult.SUCCESS);

        authService.handleSync(player, new String[]{"mallory", "theirpw"});

        assertTrue(authService.isFrozen(uuid), "a password change must not open the character up");
        assertEquals("alice", credsStore.get(uuid).get("username"));
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
