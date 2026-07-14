package org.raindrippy.serversideutils;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.GameMode;
import org.bukkit.entity.Arrow;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Event-flow tests for {@link PluginEventListener} — freeze enforcement, combat tagging, and the
 * combat-log -> strike -> auto-ban pipeline. Events are Mockito mocks (only the accessors the
 * handler uses are stubbed); players are real {@link PlayerMock}s; collaborators are mocks.
 */
@SuppressWarnings("deprecation")
class PluginEventListenerTest {

    private ServerMock server;
    private Main plugin;
    private AuthService authService;
    private CredentialsManager credentialsManager;
    private CryptoService cryptoService;
    private ApiClient apiClient;
    private ScoreboardService scoreboardService;
    private ConfigManager configManager;
    private CombatManager combatManager;
    private CombatLogManager combatLogManager;
    private WarningsManager warningsManager;

    private Map<UUID, List<String>> warningsStore;
    private Map<UUID, JSONObject> credsStore;
    private Map<UUID, GameMode> gameModeMap;

    private PluginEventListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = mock(Main.class);
        lenient().when(plugin.isEnabled()).thenReturn(true);
        lenient().when(plugin.getName()).thenReturn("ServerUtils");
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("PluginEventListenerTest"));

        authService = mock(AuthService.class);
        credentialsManager = mock(CredentialsManager.class);
        cryptoService = mock(CryptoService.class);
        apiClient = mock(ApiClient.class);
        scoreboardService = mock(ScoreboardService.class);
        configManager = mock(ConfigManager.class);
        combatManager = mock(CombatManager.class);
        combatLogManager = mock(CombatLogManager.class);
        warningsManager = mock(WarningsManager.class);

        warningsStore = new HashMap<>();
        credsStore = new HashMap<>();
        gameModeMap = new HashMap<>();
        lenient().when(warningsManager.getWarnings()).thenReturn(warningsStore);
        lenient().when(credentialsManager.getCredentials()).thenReturn(credsStore);
        lenient().when(authService.getGameModeMap()).thenReturn(gameModeMap);

        Set<String> hiddenCommands = new HashSet<>(List.of("sync"));
        listener = new PluginEventListener(plugin, authService, credentialsManager, cryptoService,
                apiClient, scoreboardService, configManager, combatManager, combatLogManager,
                warningsManager, hiddenCommands);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @SuppressWarnings("unchecked")
    private static JSONObject creds(String username) {
        JSONObject o = new JSONObject();
        o.put("username", username);
        return o;
    }

    // ---------- freeze enforcement ----------

    @Test
    @DisplayName("a frozen player's chat is cancelled")
    void frozenChatCancelled() {
        PlayerMock player = server.addPlayer("Frosty");
        when(authService.isFrozen(player.getUniqueId())).thenReturn(true);

        AsyncPlayerChatEvent event = mock(AsyncPlayerChatEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onChat(event);

        verify(event).setCancelled(true);
    }

    @Test
    @DisplayName("an unfrozen player's chat is not cancelled")
    void unfrozenChatAllowed() {
        PlayerMock player = server.addPlayer("Warm");
        when(authService.isFrozen(player.getUniqueId())).thenReturn(false);

        AsyncPlayerChatEvent event = mock(AsyncPlayerChatEvent.class);
        lenient().when(event.getPlayer()).thenReturn(player);

        listener.onChat(event);

        verify(event, never()).setCancelled(true);
    }

    // ---------- hidden / combat-blocked commands ----------

    @Test
    @DisplayName("a hidden /sync command is cancelled and routed to AuthService.handleSync")
    void hiddenSyncRouted() {
        PlayerMock player = server.addPlayer("Syncer");
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getMessage()).thenReturn("/sync bob secret");
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerCommand(event);

        verify(event).setCancelled(true);
        verify(authService).handleSync(eq(player), aryEq(new String[]{"bob", "secret"}));
    }

    @Test
    @DisplayName("/home is blocked while in combat")
    void homeBlockedInCombat() {
        PlayerMock player = server.addPlayer("Fighter");
        when(combatManager.isInCombat(player)).thenReturn(true);
        when(combatManager.getRemainingSeconds(player)).thenReturn(9L);

        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getMessage()).thenReturn("/home");
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerCommand(event);

        verify(event).setCancelled(true);
        assertMessageContains(player, "combat");
    }

    @Test
    @DisplayName("/home is allowed when not in combat")
    void homeAllowedOutOfCombat() {
        PlayerMock player = server.addPlayer("Peaceful");
        when(combatManager.isInCombat(player)).thenReturn(false);

        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getMessage()).thenReturn("/home");
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerCommand(event);

        verify(event, never()).setCancelled(true);
    }

    // ---------- combat tagging ----------

    @Test
    @DisplayName("a player attacking another player tags both as in combat")
    void pvpTagsBoth() {
        PlayerMock attacker = server.addPlayer("Attacker");
        PlayerMock victim = server.addPlayer("Victim");
        when(authService.isFrozen(attacker.getUniqueId())).thenReturn(false);
        when(combatManager.isInCombat(attacker)).thenReturn(false);
        when(combatManager.isInCombat(victim)).thenReturn(false);

        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(attacker);
        lenient().when(event.getEntity()).thenReturn(victim);

        listener.onPlayerAttack(event);

        verify(combatManager).tagPlayer(attacker.getUniqueId());
        verify(combatManager).tagPlayer(victim.getUniqueId());
    }

    @Test
    @DisplayName("a projectile shot by a player resolves the shooter as the attacker")
    void projectileResolvesShooter() {
        PlayerMock shooter = server.addPlayer("Archer");
        PlayerMock victim = server.addPlayer("Target");
        when(authService.isFrozen(shooter.getUniqueId())).thenReturn(false);
        when(combatManager.isInCombat(shooter)).thenReturn(false);
        when(combatManager.isInCombat(victim)).thenReturn(false);

        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(shooter);
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(arrow);
        lenient().when(event.getEntity()).thenReturn(victim);

        listener.onPlayerAttack(event);

        verify(combatManager).tagPlayer(shooter.getUniqueId());
        verify(combatManager).tagPlayer(victim.getUniqueId());
    }

    @Test
    @DisplayName("a frozen attacker's hit is cancelled and does not tag combat")
    void frozenAttackerCancelled() {
        PlayerMock attacker = server.addPlayer("FrozenAttacker");
        PlayerMock victim = server.addPlayer("Bystander");
        when(authService.isFrozen(attacker.getUniqueId())).thenReturn(true);

        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(attacker);
        lenient().when(event.getEntity()).thenReturn(victim);

        listener.onPlayerAttack(event);

        verify(event).setCancelled(true);
        verify(combatManager, never()).tagPlayer(attacker.getUniqueId());
    }

    // ---------- combat-log -> strike -> ban pipeline ----------

    @Test
    @DisplayName("quitting in combat records a strike, files a warning, and clears combat")
    void quitInCombatRecordsStrike() {
        PlayerMock player = server.addPlayer("Logger");
        UUID uuid = player.getUniqueId();
        when(combatManager.isInCombat(player)).thenReturn(true);
        when(combatLogManager.recordStrike(uuid)).thenReturn(1);

        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerQuit(event);

        verify(combatLogManager).recordStrike(uuid);
        verify(warningsManager).save();
        verify(combatManager).clearCombat(player);
        // A warning was filed mentioning the strike number.
        List<String> filed = warningsStore.get(uuid);
        org.junit.jupiter.api.Assertions.assertEquals(1, filed.size());
        org.junit.jupiter.api.Assertions.assertTrue(filed.get(0).contains("strike #1"));
        // Below the 3-strike threshold -> no ban.
        verify(apiClient, never()).banAccount(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("the 3rd strike with linked credentials requests an account ban")
    void thirdStrikeWithCredsBans() {
        // A plain Player mock is used (not PlayerMock) so the ban task's async kickPlayer is a
        // harmless no-op here; the async-kick defect itself is pinned in
        // PluginEventListenerAsyncKickBugTest.
        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        lenient().when(player.getName()).thenReturn("Repeat");
        when(combatManager.isInCombat(player)).thenReturn(true);
        when(combatLogManager.recordStrike(uuid)).thenReturn(3);
        credsStore.put(uuid, creds("bobaccount"));
        when(apiClient.banAccount("bobaccount")).thenReturn(true);

        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerQuit(event);
        server.getScheduler().waitAsyncTasksFinished();

        verify(apiClient).banAccount("bobaccount");
    }

    @Test
    @DisplayName("the 3rd strike without linked credentials does not ban")
    void thirdStrikeNoCredsNoBan() {
        PlayerMock player = server.addPlayer("Anon");
        UUID uuid = player.getUniqueId();
        when(combatManager.isInCombat(player)).thenReturn(true);
        when(combatLogManager.recordStrike(uuid)).thenReturn(3);
        // credsStore has no entry for this player.

        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerQuit(event);
        server.getScheduler().waitAsyncTasksFinished();

        verify(apiClient, never()).banAccount(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("quitting while not in combat records nothing")
    void quitNotInCombatNoStrike() {
        PlayerMock player = server.addPlayer("Clean");
        when(combatManager.isInCombat(player)).thenReturn(false);

        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerQuit(event);

        verify(combatLogManager, never()).recordStrike(org.mockito.ArgumentMatchers.any());
        verify(warningsManager, never()).save();
    }

    // ---------- join ----------

    @Test
    @DisplayName("a player with no saved credentials is frozen on join")
    void joinWithoutCredentialsFreezes() {
        PlayerMock player = server.addPlayer("NewGuy");
        UUID uuid = player.getUniqueId();
        when(combatLogManager.isPending(uuid)).thenReturn(false);
        // credsStore has no entry -> else branch freezes.

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerJoin(event);

        verify(scoreboardService).enable(player);
        verify(authService).freezePlayer(player);
    }

    private static void assertMessageContains(PlayerMock player, String needle) {
        String msg;
        while ((msg = player.nextMessage()) != null) {
            if (msg.contains(needle)) {
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("expected a message containing '" + needle + "'");
    }
}
