package org.raindrippy.serversideutils;

import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

import org.mockito.Mockito;

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
    private PendingPaymentsManager pendingPaymentsManager;

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
        pendingPaymentsManager = mock(PendingPaymentsManager.class);

        warningsStore = new HashMap<>();
        credsStore = new HashMap<>();
        gameModeMap = new HashMap<>();
        lenient().when(warningsManager.getWarnings()).thenReturn(warningsStore);
        lenient().when(credentialsManager.getCredentials()).thenReturn(credsStore);
        lenient().when(authService.getGameModeMap()).thenReturn(gameModeMap);

        listener = new PluginEventListener(plugin, authService, credentialsManager, cryptoService,
                apiClient, scoreboardService, configManager, combatManager, combatLogManager,
                warningsManager, pendingPaymentsManager);
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
    @DisplayName("a log-censored command like /tell still runs and is not swallowed")
    void censoredCommandStillRuns() {
        PlayerMock player = server.addPlayer("Talker");
        when(authService.isFrozen(player.getUniqueId())).thenReturn(false);
        when(combatManager.isInCombat(player)).thenReturn(false);

        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getMessage()).thenReturn("/tell Bob hello there");
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerCommand(event);

        // Cancelling would have delivered nothing to Bob; keeping it out of the log files is
        // CommandLogFilter's job, not this handler's.
        verify(event, never()).setCancelled(true);
        assertNoMessageContains(player, "Hidden command processed");
    }

    @Test
    @DisplayName("/msg is likewise left alone")
    void censoredMsgStillRuns() {
        PlayerMock player = server.addPlayer("Messenger");
        when(authService.isFrozen(player.getUniqueId())).thenReturn(false);
        when(combatManager.isInCombat(player)).thenReturn(false);

        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getMessage()).thenReturn("/msg Bob hi");
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerCommand(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    @DisplayName("/sync preserves the case of the password it forwards")
    void syncPreservesPasswordCase() {
        PlayerMock player = server.addPlayer("CaseSensitive");
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getMessage()).thenReturn("/sync Alice MixedCasePw");
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerCommand(event);

        verify(authService).handleSync(eq(player), aryEq(new String[]{"Alice", "MixedCasePw"}));
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

    @Test
    @DisplayName("an unauthenticated player's commands are blocked")
    void frozenCommandsBlocked() {
        PlayerMock player = server.addPlayer("Parked");
        when(authService.isFrozen(player.getUniqueId())).thenReturn(true);

        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getMessage()).thenReturn("/home");
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerCommand(event);

        // Otherwise a teleport command walks them straight out of the waiting chamber.
        verify(event).setCancelled(true);
        assertMessageContains(player, "authenticate before using commands");
    }

    @Test
    @DisplayName("/sync is still allowed while unauthenticated")
    void frozenSyncStillAllowed() {
        PlayerMock player = server.addPlayer("Parked2");
        when(authService.isFrozen(player.getUniqueId())).thenReturn(true);

        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getMessage()).thenReturn("/sync bob secret");
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerCommand(event);

        verify(authService).handleSync(eq(player), aryEq(new String[]{"bob", "secret"}));
    }

    @Test
    @DisplayName("an authenticated player's commands are untouched")
    void unfrozenCommandsAllowed() {
        PlayerMock player = server.addPlayer("Free");
        when(authService.isFrozen(player.getUniqueId())).thenReturn(false);
        when(combatManager.isInCombat(player)).thenReturn(false);

        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getMessage()).thenReturn("/home");
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerCommand(event);

        verify(event, never()).setCancelled(true);
    }

    // ---------- waiting-chamber leash ----------

    @Test
    @DisplayName("a position change asks the auth service to keep the player in the chamber")
    void moveChecksChamber() {
        PlayerMock player = server.addPlayer("Walker");
        org.bukkit.World world = server.addSimpleWorld("authhub");

        org.bukkit.event.player.PlayerMoveEvent event =
                mock(org.bukkit.event.player.PlayerMoveEvent.class);
        when(event.getFrom()).thenReturn(new org.bukkit.Location(world, 0, 64, 0));
        when(event.getTo()).thenReturn(new org.bukkit.Location(world, 40, 64, 0));
        when(event.getPlayer()).thenReturn(player);

        listener.onMove(event);

        verify(authService).keepInChamber(player);
    }

    @Test
    @DisplayName("looking around does not trigger the chamber check")
    void lookingAroundIsIgnored() {
        PlayerMock player = server.addPlayer("Looker");
        org.bukkit.World world = server.addSimpleWorld("authhub");

        org.bukkit.event.player.PlayerMoveEvent event =
                mock(org.bukkit.event.player.PlayerMoveEvent.class);
        // Same block, different yaw: fires constantly and must stay cheap.
        when(event.getFrom()).thenReturn(new org.bukkit.Location(world, 5.2, 64, 5.2, 0f, 0f));
        when(event.getTo()).thenReturn(new org.bukkit.Location(world, 5.3, 64, 5.3, 90f, 0f));
        lenient().when(event.getPlayer()).thenReturn(player);

        listener.onMove(event);

        verify(authService, never()).keepInChamber(org.mockito.ArgumentMatchers.any());
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

    // ---------- join: network check ----------

    /**
     * A listener wired with a real {@link CryptoService} (the shared one is a mock) so the network
     * fingerprints it derives match the ones a test computes for itself.
     */
    private PluginEventListener listenerWith(CryptoService realCrypto) {
        return new PluginEventListener(plugin, authService, credentialsManager, realCrypto,
                apiClient, scoreboardService, configManager, combatManager, combatLogManager,
                warningsManager, pendingPaymentsManager);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a join from a remembered network still auto-authenticates")
    void joinFromKnownNetworkAutoAuthenticates() throws Exception {
        CryptoService crypto = new CryptoService("0123456789abcdef");
        NetworkGuard guard = new NetworkGuard(crypto);
        PlayerMock player = server.addPlayer("Homebody");
        player.setAddress(new InetSocketAddress(InetAddress.getByName("1.2.3.4"), 25565));

        JSONObject creds = creds("bob");
        creds.put("password", crypto.encrypt("pw"));
        guard.remember(creds, guard.fingerprint(player));
        credsStore.put(player.getUniqueId(), creds);

        when(apiClient.queryStatus("bob", player)).thenReturn(true);
        when(apiClient.queryLogin("bob", "pw")).thenReturn(true);

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listenerWith(crypto).onPlayerJoin(event);
        server.getScheduler().waitAsyncTasksFinished();

        verify(apiClient).queryLogin("bob", "pw");
        verify(authService, never()).freezePlayer(player);
        verify(authService, never()).freezePlayer(eq(player), Mockito.anyString());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a join from an unrecognised network is frozen and keeps the saved credentials")
    void joinFromUnknownNetworkRequiresResync() throws Exception {
        CryptoService crypto = new CryptoService("0123456789abcdef");
        NetworkGuard guard = new NetworkGuard(crypto);
        PlayerMock player = server.addPlayer("Traveller");
        UUID uuid = player.getUniqueId();

        JSONObject creds = creds("bob");
        creds.put("password", crypto.encrypt("pw"));
        // Remembered from the home network...
        player.setAddress(new InetSocketAddress(InetAddress.getByName("1.2.3.4"), 25565));
        guard.remember(creds, guard.fingerprint(player));
        credsStore.put(uuid, creds);
        // ...but joining from somewhere else entirely.
        player.setAddress(new InetSocketAddress(InetAddress.getByName("9.9.9.9"), 25565));

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listenerWith(crypto).onPlayerJoin(event);
        server.getScheduler().waitAsyncTasksFinished();

        verify(authService).freezePlayer(eq(player), Mockito.anyString());
        // The saved account must survive: the ban pipeline still needs the username.
        org.junit.jupiter.api.Assertions.assertTrue(credsStore.containsKey(uuid));
        verify(apiClient, never()).queryLogin(Mockito.anyString(), Mockito.anyString());
        verify(apiClient, never()).queryStatus(Mockito.anyString(), Mockito.any());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a legacy credentials entry with no remembered network requires one re-sync")
    void legacyEntryRequiresResync() throws Exception {
        CryptoService crypto = new CryptoService("0123456789abcdef");
        PlayerMock player = server.addPlayer("Veteran");
        player.setAddress(new InetSocketAddress(InetAddress.getByName("1.2.3.4"), 25565));

        // Saved before this feature existed -> no "knownNets" key at all.
        JSONObject creds = creds("bob");
        creds.put("password", crypto.encrypt("pw"));
        credsStore.put(player.getUniqueId(), creds);

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listenerWith(crypto).onPlayerJoin(event);
        server.getScheduler().waitAsyncTasksFinished();

        verify(authService).freezePlayer(eq(player), Mockito.anyString());
        verify(apiClient, never()).queryLogin(Mockito.anyString(), Mockito.anyString());
    }

    // ---------- offline payment notices ----------

    @Test
    @DisplayName("money received while offline is reported on join and the notice is then cleared")
    void joinDeliversPendingPaymentNotice() {
        PlayerMock player = server.addPlayer("Bob");
        UUID uuid = player.getUniqueId();
        when(pendingPaymentsManager.hasPending(uuid)).thenReturn(true);
        when(pendingPaymentsManager.getPending(uuid)).thenReturn(List.of(
                new PendingPaymentsManager.PendingPayment("Alice", 50.0),
                new PendingPaymentsManager.PendingPayment("Carol", 12.5)));

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerJoin(event);
        // The notice is delayed 40 ticks so it isn't lost in join spam.
        server.getScheduler().performTicks(45);

        assertMessageContains(player, "You received $62.50 while you were offline");
        verify(pendingPaymentsManager).clearPending(uuid);
    }

    @Test
    @DisplayName("a join with no pending payments sends no notice and clears nothing")
    void joinWithoutPendingPaymentsSaysNothing() {
        PlayerMock player = server.addPlayer("Bob");
        UUID uuid = player.getUniqueId();
        when(pendingPaymentsManager.hasPending(uuid)).thenReturn(false);

        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        listener.onPlayerJoin(event);
        server.getScheduler().performTicks(45);

        verify(pendingPaymentsManager, never()).clearPending(uuid);
    }

    private static void assertNoMessageContains(PlayerMock player, String needle) {
        String msg;
        while ((msg = player.nextMessage()) != null) {
            if (msg.contains(needle)) {
                org.junit.jupiter.api.Assertions.fail("unexpected message containing '" + needle + "'");
            }
        }
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
