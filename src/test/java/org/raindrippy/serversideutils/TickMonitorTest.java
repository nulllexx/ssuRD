package org.raindrippy.serversideutils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.OptionalDouble;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link TickMonitor}. */
class TickMonitorTest {

    private static final long MS = 1_000_000L;

    /** A clock the test advances by hand. */
    private static final class FakeClock {
        long now;
    }

    private static TickMonitor monitor(FakeClock clock) {
        return new TickMonitor(() -> clock.now, TickMonitor.MsptSource.NONE);
    }

    /** Records {@code ticks} ticks spaced {@code gapMs} apart, starting at the clock's current time. */
    private static void tick(TickMonitor monitor, FakeClock clock, int ticks, long gapMs) {
        for (int i = 0; i < ticks; i++) {
            monitor.run();
            clock.now += gapMs * MS;
        }
    }

    @Test
    @DisplayName("a healthy 50ms tick rate reads as 20 TPS")
    void healthyServer() {
        FakeClock clock = new FakeClock();
        TickMonitor monitor = monitor(clock);
        tick(monitor, clock, 20 * 70, 50);

        assertEquals(20.0, monitor.getTps(5).getAsDouble(), 0.01);
        assertEquals(20.0, monitor.getTps(60).getAsDouble(), 0.01);
    }

    @Test
    @DisplayName("TPS is capped at 20 even if ticks arrive faster")
    void cappedAtTwenty() {
        FakeClock clock = new FakeClock();
        TickMonitor monitor = monitor(clock);
        tick(monitor, clock, 400, 40);

        assertEquals(20.0, monitor.getTps(5).getAsDouble(), 0.01);
    }

    @Test
    @DisplayName("100ms ticks read as 10 TPS")
    void halfSpeed() {
        FakeClock clock = new FakeClock();
        TickMonitor monitor = monitor(clock);
        tick(monitor, clock, 200, 100);

        assertEquals(10.0, monitor.getTps(5).getAsDouble(), 0.01);
    }

    @Test
    @DisplayName("a partial window is measured over the time actually run")
    void partialWindow() {
        FakeClock clock = new FakeClock();
        TickMonitor monitor = monitor(clock);
        tick(monitor, clock, 41, 100); // ~4 s of 10 TPS, well short of a minute

        assertEquals(10.0, monitor.getTps(60).getAsDouble(), 0.3);
    }

    @Test
    @DisplayName("a full stall shows up as low TPS")
    void stall() {
        FakeClock clock = new FakeClock();
        TickMonitor monitor = monitor(clock);
        tick(monitor, clock, 200, 50);
        clock.now += 4_000 * MS; // server hangs for 4 seconds

        assertEquals(4.0, monitor.getTps(5).getAsDouble(), 0.3);
    }

    @Test
    @DisplayName("with fewer than two ticks there is no TPS yet")
    void tooFewTicks() {
        FakeClock clock = new FakeClock();
        TickMonitor monitor = monitor(clock);
        assertFalse(monitor.getTps(5).isPresent());
        monitor.run();
        assertFalse(monitor.getTps(5).isPresent());
    }

    @Test
    @DisplayName("the ring buffer wraps without breaking the reading")
    void wraps() {
        FakeClock clock = new FakeClock();
        TickMonitor monitor = monitor(clock);
        tick(monitor, clock, TickMonitor.CAPACITY + 500, 50);

        assertEquals(20.0, monitor.getTps(300).getAsDouble(), 0.01);
    }

    // ----- MSPT source resolution -----

    public static class PaperLikeServer {
        public double getAverageTickTime() {
            return 12.5;
        }
    }

    public static class FakeMinecraftServer {
        public long getAverageTickTimeNanos() {
            return 8 * MS;
        }
    }

    public static class CraftLikeServer {
        public FakeMinecraftServer getServer() {
            return new FakeMinecraftServer();
        }
    }

    public static class OldMinecraftServer {
        @SuppressWarnings("unused")
        private final long[] tickTimes = {10 * MS, 20 * MS};
    }

    public static class OldCraftLikeServer {
        public OldMinecraftServer getServer() {
            return new OldMinecraftServer();
        }
    }

    @Test
    @DisplayName("MSPT is read from Paper's getAverageTickTime when present")
    void msptFromPaper() {
        TickMonitor.MsptSource source = TickMonitor.resolveMsptSource(new PaperLikeServer(), null);
        assertEquals("paper", source.name());
        assertEquals(OptionalDouble.of(12.5), monitorWith(source).getMspt());
    }

    @Test
    @DisplayName("MSPT is read from MinecraftServer#getAverageTickTimeNanos behind CraftServer")
    void msptFromNmsMethod() {
        TickMonitor.MsptSource source = TickMonitor.resolveMsptSource(new CraftLikeServer(), null);
        assertEquals(8.0, monitorWith(source).getMspt().getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("MSPT falls back to averaging the tick-times array")
    void msptFromNmsField() {
        TickMonitor.MsptSource source = TickMonitor.resolveMsptSource(new OldCraftLikeServer(), null);
        assertEquals(15.0, monitorWith(source).getMspt().getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("an unknown server has no MSPT source")
    void msptUnavailable() {
        TickMonitor.MsptSource source = TickMonitor.resolveMsptSource(new Object(), null);
        assertSame(TickMonitor.MsptSource.NONE, source);
        assertFalse(monitorWith(source).getMspt().isPresent());
    }

    private static TickMonitor monitorWith(TickMonitor.MsptSource source) {
        return new TickMonitor(() -> 0L, source);
    }
}
