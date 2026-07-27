package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.mockbukkit.mockbukkit.MockBukkit;

/** Persistence tests for {@link PendingPaymentsManager}, the offline-payment notice store. */
class PendingPaymentsManagerTest {

    @TempDir
    Path dataFolder;

    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = mock(JavaPlugin.class);
        lenient().when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("PendingPaymentsManagerTest"));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PendingPaymentsManager freshManager() {
        PendingPaymentsManager mgr = new PendingPaymentsManager(plugin);
        mgr.setup();
        return mgr;
    }

    private PendingPaymentsManager reload() {
        PendingPaymentsManager mgr = new PendingPaymentsManager(plugin);
        mgr.setup();
        mgr.load();
        return mgr;
    }

    @Test
    @DisplayName("setup creates the pending_payments.yml file")
    void setupCreatesFile() {
        freshManager();
        assertTrue(new File(dataFolder.toFile(), "pending_payments.yml").exists());
    }

    @Test
    @DisplayName("a player with no notices reports nothing pending")
    void noPendingByDefault() {
        PendingPaymentsManager mgr = freshManager();
        UUID uuid = UUID.randomUUID();
        assertFalse(mgr.hasPending(uuid));
        assertTrue(mgr.getPending(uuid).isEmpty());
    }

    @Test
    @DisplayName("recorded notices survive a save + reload, in order, with sender and amount intact")
    void persistenceRoundTrip() {
        UUID bob = UUID.randomUUID();
        PendingPaymentsManager mgr = freshManager();
        mgr.record(bob, "Alice", 50.0);
        mgr.record(bob, "Carol", 12.5);

        List<PendingPaymentsManager.PendingPayment> pending = reload().getPending(bob);
        assertEquals(2, pending.size());
        assertEquals("Alice", pending.get(0).getFrom());
        assertEquals(50.0, pending.get(0).getAmount());
        assertEquals("Carol", pending.get(1).getFrom());
        assertEquals(12.5, pending.get(1).getAmount());
    }

    @Test
    @DisplayName("notices are kept separate per recipient")
    void noticesAreScopedToRecipient() {
        UUID bob = UUID.randomUUID();
        UUID dave = UUID.randomUUID();
        PendingPaymentsManager mgr = freshManager();
        mgr.record(bob, "Alice", 50.0);
        mgr.record(dave, "Alice", 7.0);

        PendingPaymentsManager reloaded = reload();
        assertEquals(1, reloaded.getPending(bob).size());
        assertEquals(7.0, reloaded.getPending(dave).get(0).getAmount());
    }

    @Test
    @DisplayName("cleared notices are not redelivered after a reload")
    void clearedNoticesDoNotResurrect() {
        UUID bob = UUID.randomUUID();
        UUID dave = UUID.randomUUID();
        PendingPaymentsManager mgr = freshManager();
        mgr.record(bob, "Alice", 50.0);
        mgr.record(dave, "Alice", 7.0);

        mgr.clearPending(bob);

        PendingPaymentsManager reloaded = reload();
        assertFalse(reloaded.hasPending(bob), "a delivered notice must not remain in pending_payments.yml");
        assertTrue(reloaded.hasPending(dave), "clearing one recipient must not drop the others");
    }

    @Test
    @DisplayName("the list returned to callers cannot mutate the store")
    void returnedListIsDefensive() {
        UUID bob = UUID.randomUUID();
        PendingPaymentsManager mgr = freshManager();
        mgr.record(bob, "Alice", 50.0);

        List<PendingPaymentsManager.PendingPayment> pending = mgr.getPending(bob);
        try {
            pending.add(new PendingPaymentsManager.PendingPayment("Mallory", 999.0));
        } catch (UnsupportedOperationException expected) {
            // immutable view — fine
        }
        assertEquals(1, mgr.getPending(bob).size());
    }

    @Test
    @DisplayName("malformed entries are skipped rather than failing the whole load")
    void malformedEntriesAreSkipped() throws Exception {
        UUID bob = UUID.randomUUID();
        Files.writeString(dataFolder.resolve("pending_payments.yml"),
                bob + ":\n"
                        + "- 'Alice;50.0'\n"
                        + "- 'Broken'\n"
                        + "- 'Carol;notanumber'\n"
                        + "- 'Dave;-5.0'\n"
                        + "- 'Erin;7.5'\n"
                        + "not-a-uuid:\n"
                        + "- 'Frank;1.0'\n");

        PendingPaymentsManager mgr = reload();
        List<PendingPaymentsManager.PendingPayment> pending = mgr.getPending(bob);
        assertEquals(2, pending.size(), "only the two well-formed entries should survive");
        assertEquals("Alice", pending.get(0).getFrom());
        assertEquals("Erin", pending.get(1).getFrom());
    }
}
