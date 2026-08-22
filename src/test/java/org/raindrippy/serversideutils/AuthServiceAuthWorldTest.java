package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * Tests the auth-world parking added on top of the freeze: an unauthenticated player is moved out
 * of their real position (so their coordinates reveal nothing) and put back on a successful /sync.
 */
class AuthServiceAuthWorldTest {

    private static final String AUTH_WORLD = "authhub";

    @TempDir
    Path dataFolder;

    private ServerMock server;
    private WorldMock overworld;
    private WorldMock authWorld;
    private ApiClient apiClient;
    private CredentialsManager credentialsManager;
    private CryptoService cryptoService;
    private AuthLocationsManager authLocations;
    private Map<UUID, JSONObject> credsStore;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        overworld = server.addSimpleWorld("world");
        authWorld = server.addSimpleWorld(AUTH_WORLD);

        JavaPlugin plugin = mock(JavaPlugin.class);
        lenient().when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("AuthServiceAuthWorldTest"));

        apiClient = mock(ApiClient.class);
        credentialsManager = mock(CredentialsManager.class);
        cryptoService = new CryptoService("0123456789abcdef");
        credsStore = new HashMap<>();
        lenient().when(credentialsManager.getCredentials()).thenReturn(credsStore);

        authLocations = new AuthLocationsManager(plugin);
        authLocations.setup();

        player = server.addPlayer("Wanderer");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private JavaPlugin pluginMock() {
        JavaPlugin p = mock(JavaPlugin.class);
        lenient().when(p.getDataFolder()).thenReturn(dataFolder.toFile());
        lenient().when(p.getLogger()).thenReturn(Logger.getLogger("AuthServiceAuthWorldTest"));
        return p;
    }

    private AuthService service() {
        return new AuthService(apiClient, credentialsManager, cryptoService,
                Logger.getLogger("AuthServiceAuthWorldTest"), authLocations, AUTH_WORLD);
    }

    @Test
    @DisplayName("freezing moves the player to the auth world and remembers where they were")
    void freezeParksInAuthWorld() {
        Location base = new Location(overworld, 1500.5, 70.0, -2200.5);
        player.teleport(base);

        service().freezePlayer(player);

        assertEquals(AUTH_WORLD, player.getWorld().getName(), "frozen players must not sit at their base");
        Location remembered = authLocations.getOrigin(player.getUniqueId());
        assertEquals("world", remembered.getWorld().getName());
        assertEquals(1500.5, remembered.getX());
        assertEquals(-2200.5, remembered.getZ());
    }

    @Test
    @DisplayName("freezing again while already parked keeps the original position")
    void refreezeDoesNotOverwriteOrigin() {
        Location base = new Location(overworld, 1500.5, 70.0, -2200.5);
        player.teleport(base);
        AuthService auth = service();

        auth.freezePlayer(player);
        // A rejoin while still unauthenticated, or a failed /sync, freezes them a second time.
        auth.freezePlayer(player);

        Location remembered = authLocations.getOrigin(player.getUniqueId());
        assertEquals("world", remembered.getWorld().getName(), "must not record the auth world itself");
        assertEquals(1500.5, remembered.getX());
    }

    @Test
    @DisplayName("a successful sync puts the player back where they were and clears the record")
    void syncReturnsPlayerHome() {
        Location base = new Location(overworld, 1500.5, 70.0, -2200.5);
        player.teleport(base);
        AuthService auth = service();
        auth.getGameModeMap().put(player.getUniqueId(), GameMode.SURVIVAL);
        auth.freezePlayer(player);
        when(apiClient.queryCredentials("user", "pass")).thenReturn(ApiClient.LoginResult.SUCCESS);

        auth.handleSync(player, new String[]{"user", "pass"});

        assertFalse(auth.isFrozen(player.getUniqueId()));
        assertEquals("world", player.getWorld().getName());
        assertEquals(1500.5, player.getLocation().getX());
        assertEquals(-2200.5, player.getLocation().getZ());
        assertFalse(authLocations.hasOrigin(player.getUniqueId()), "the record must not linger");
    }

    @Test
    @DisplayName("a player whose recorded world is gone is sent to spawn, not left in the auth world")
    void missingOriginWorldFallsBackToSpawn() {
        // Park them from a world that will not survive, so the origin genuinely cannot resolve.
        // (Deleting and re-creating "world" would silently take the normal restore path instead.)
        WorldMock oldSeason = server.addSimpleWorld("season8");
        player.teleport(new Location(oldSeason, 10, 64, 10));
        AuthService auth = service();
        auth.freezePlayer(player);

        // Season reset: the world the origin pointed at is gone; the main world remains.
        server.removeWorld(oldSeason);
        overworld.setSpawnLocation(new Location(overworld, 8, 65, 8));
        assertTrue(authLocations.hasOrigin(player.getUniqueId()), "the record should still exist");
        assertNull(authLocations.getOrigin(player.getUniqueId()), "but must not resolve");

        auth.unfreezePlayer(player);

        assertEquals("world", player.getWorld().getName(), "must not be left in the auth world");
        assertEquals(8, player.getLocation().getBlockX(), "should land on the main world spawn");
        assertFalse(authLocations.hasOrigin(player.getUniqueId()), "the dead record must be cleared");
    }

    @Test
    @DisplayName("the pre-freeze gamemode is restored after a restart, not the holding one")
    void gameModeSurvivesRestart() {
        player.setGameMode(GameMode.CREATIVE);
        player.teleport(new Location(overworld, 20, 64, 20));
        service().freezePlayer(player);
        assertEquals(GameMode.ADVENTURE, player.getGameMode(), "held in adventure while parked");

        // Restart: a fresh AuthService (empty gameModeMap) reading the persisted record. Without
        // persistence the player would be handed back the holding gamemode.
        AuthLocationsManager reloaded = new AuthLocationsManager(pluginMock());
        reloaded.setup();
        reloaded.load();
        AuthService afterRestart = new AuthService(apiClient, credentialsManager, cryptoService,
                Logger.getLogger("AuthServiceAuthWorldTest"), reloaded, AUTH_WORLD);

        afterRestart.unfreezePlayer(player);

        assertEquals(GameMode.CREATIVE, player.getGameMode());
        assertEquals(20, player.getLocation().getBlockX());
    }

    @Test
    @DisplayName("a rejoin while parked does not overwrite the recorded gamemode")
    void refreezeKeepsOriginalGameMode() {
        player.setGameMode(GameMode.CREATIVE);
        player.teleport(new Location(overworld, 20, 64, 20));
        AuthService auth = service();

        auth.freezePlayer(player);
        // Now in adventure, in the auth world; a second freeze must not record that as "before".
        auth.freezePlayer(player);
        auth.unfreezePlayer(player);

        assertEquals(GameMode.CREATIVE, player.getGameMode());
    }

    // ---------- waiting-chamber leash ----------

    @Test
    @DisplayName("a parked player who wanders far from the chamber is pulled back to spawn")
    void leashPullsBackWanderer() {
        authWorld.setSpawnLocation(new Location(authWorld, 0, 64, 0));
        AuthService auth = service();
        auth.freezePlayer(player);

        player.teleport(new Location(authWorld, 400, 64, 0));
        assertTrue(auth.keepInChamber(player));

        assertEquals(0, player.getLocation().getBlockX(), "should be back at the chamber");
    }

    @Test
    @DisplayName("a parked player moving inside the chamber is left alone")
    void leashIgnoresNormalMovement() {
        authWorld.setSpawnLocation(new Location(authWorld, 0, 64, 0));
        AuthService auth = service();
        auth.freezePlayer(player);

        player.teleport(new Location(authWorld, 12, 64, 7));
        assertFalse(auth.keepInChamber(player));

        assertEquals(12, player.getLocation().getBlockX(), "movement within the room must be free");
    }

    @Test
    @DisplayName("an authenticated player in the auth world is not leashed")
    void leashOnlyAppliesWhileParked() {
        authWorld.setSpawnLocation(new Location(authWorld, 0, 64, 0));
        AuthService auth = service();
        // Never frozen: e.g. an admin looking around the waiting area.
        player.teleport(new Location(authWorld, 400, 64, 0));

        assertFalse(auth.keepInChamber(player));
        assertEquals(400, player.getLocation().getBlockX());
    }

    @Test
    @DisplayName("a parked player outside the auth world is not dragged into it")
    void leashDoesNotApplyOutsideAuthWorld() {
        server.removeWorld(authWorld);
        AuthService auth = service();
        // No auth world at freeze time, so they stayed in the overworld.
        player.teleport(new Location(overworld, 900, 64, 900));
        auth.freezePlayer(player);

        assertFalse(auth.keepInChamber(player));
        assertEquals(900, player.getLocation().getBlockX());
    }

    @Test
    @DisplayName("with no auth world configured the freeze behaves exactly as before")
    void withoutAuthWorldNothingMoves() {
        Location base = new Location(overworld, 42, 64, 42);
        player.teleport(base);

        // The 4-arg constructor is the pre-existing wiring: no locations manager, no auth world.
        new AuthService(apiClient, credentialsManager, cryptoService,
                Logger.getLogger("AuthServiceAuthWorldTest")).freezePlayer(player);

        assertEquals("world", player.getWorld().getName());
        assertEquals(42, player.getLocation().getX());
        assertFalse(authLocations.hasOrigin(player.getUniqueId()));
    }

    @Test
    @DisplayName("a missing auth world still freezes the player instead of failing")
    void missingAuthWorldStillFreezes() {
        server.removeWorld(authWorld);
        player.teleport(new Location(overworld, 5, 64, 5));

        AuthService auth = service();
        auth.freezePlayer(player);

        assertTrue(auth.isFrozen(player.getUniqueId()), "the freeze must not depend on the world existing");
        assertEquals(GameMode.ADVENTURE, player.getGameMode());
        assertEquals("world", player.getWorld().getName());
        assertFalse(authLocations.hasOrigin(player.getUniqueId()));
    }

    @Test
    @DisplayName("a player stranded in the auth world with no record is rescued to spawn")
    void strandedPlayerRescued() {
        overworld.setSpawnLocation(new Location(overworld, 3, 65, 3));
        player.teleport(new Location(authWorld, 0, 64, 0));

        service().unfreezePlayer(player);

        assertEquals("world", player.getWorld().getName());
    }
}
