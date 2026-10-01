package org.raindrippy.serversideutils;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.OptionalDouble;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Measures server TPS by timestamping every tick from a 1-tick repeating task, and reads MSPT
 * best-effort from the server internals. TPS is measured here rather than read from the server
 * because the server runs Arclight (NeoForge + Bukkit), which has no Paper {@code getTPS()}.
 */
public class TickMonitor implements Runnable {
    /** Five minutes of ticks at 20 TPS -- enough for the longest window /ping reports. */
    static final int CAPACITY = 6000;
    private static final double NANOS_PER_SECOND = 1_000_000_000d;
    private static final double MAX_TPS = 20.0;

    private final LongSupplier clock;
    private final MsptSource msptSource;
    private final long[] ticks = new long[CAPACITY];
    private int head; // next write index
    private int count;

    public TickMonitor(LongSupplier clock, MsptSource msptSource) {
        this.clock = clock;
        this.msptSource = msptSource;
    }

    public void start(Plugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, 1L);
    }

    @Override
    public void run() {
        ticks[head] = clock.getAsLong();
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) count++;
    }

    /**
     * Ticks per second over the last {@code windowSeconds}, capped at 20. Empty until at least two
     * ticks have been recorded. While the monitor has been running for less than the window, the
     * rate is taken over the time it has been running instead.
     */
    public OptionalDouble getTps(int windowSeconds) {
        if (count < 2) return OptionalDouble.empty();
        long now = clock.getAsLong();
        long cutoff = now - (long) (windowSeconds * NANOS_PER_SECOND);
        long oldest = ticks[(head - count + CAPACITY) % CAPACITY];

        int inWindow = 0;
        for (int i = 1; i <= count; i++) {
            long t = ticks[(head - i + CAPACITY) % CAPACITY];
            if (t < cutoff) break;
            inWindow++;
        }

        double tps;
        if (oldest <= cutoff || count == CAPACITY) {
            tps = inWindow / (double) windowSeconds;
        } else {
            // Partial window: N ticks span N-1 intervals, measured up to now so a stall still counts.
            double span = (now - oldest) / NANOS_PER_SECOND;
            if (span <= 0) return OptionalDouble.empty();
            tps = (inWindow - 1) / span;
        }
        return OptionalDouble.of(Math.min(MAX_TPS, tps));
    }

    /** Average milliseconds per tick, if the server exposes it. */
    public OptionalDouble getMspt() {
        try {
            return msptSource.read();
        } catch (Throwable t) {
            return OptionalDouble.empty();
        }
    }

    public String getMsptSourceName() {
        return msptSource.name();
    }

    /** Where MSPT is read from. */
    public interface MsptSource {
        OptionalDouble read() throws Exception;

        String name();

        MsptSource NONE = new MsptSource() {
            @Override
            public OptionalDouble read() {
                return OptionalDouble.empty();
            }

            @Override
            public String name() {
                return "none";
            }
        };
    }

    /**
     * Finds a way to read MSPT from {@code server} (the Bukkit Server instance), trying the Paper
     * API first, then the vanilla MinecraftServer behind CraftServer (Mojang names, which is what
     * NeoForge/Arclight run with). Each candidate is test-read once; anything that fails falls
     * through, ending at {@link MsptSource#NONE}. Never throws.
     */
    public static MsptSource resolveMsptSource(Object server, Logger logger) {
        // 1. Paper: Server#getAverageTickTime() -> double milliseconds.
        try {
            Method m = server.getClass().getMethod("getAverageTickTime");
            MsptSource paper = named("paper", () -> OptionalDouble.of(((Number) m.invoke(server)).doubleValue()));
            if (works(paper)) return paper;
        } catch (Throwable ignored) {
        }

        // 2. NMS: CraftServer#getServer() -> MinecraftServer.
        Object minecraftServer;
        try {
            minecraftServer = server.getClass().getMethod("getServer").invoke(server);
        } catch (Throwable t) {
            minecraftServer = null;
        }
        if (minecraftServer != null) {
            Object mc = minecraftServer;
            try {
                Method m = mc.getClass().getMethod("getAverageTickTimeNanos");
                MsptSource nanos = named("nms getAverageTickTimeNanos",
                        () -> OptionalDouble.of(((Number) m.invoke(mc)).longValue() / 1_000_000d));
                if (works(nanos)) return nanos;
            } catch (Throwable ignored) {
            }
            for (String fieldName : new String[]{"tickTimesNanos", "tickTimes"}) {
                Field f = findField(mc.getClass(), fieldName);
                if (f == null) continue;
                try {
                    f.setAccessible(true);
                    MsptSource field = named("nms " + fieldName, () -> {
                        long[] times = (long[]) f.get(mc);
                        if (times == null || times.length == 0) return OptionalDouble.empty();
                        long sum = 0;
                        for (long t : times) sum += t;
                        return OptionalDouble.of(sum / (double) times.length / 1_000_000d);
                    });
                    if (works(field)) return field;
                } catch (Throwable ignored) {
                }
            }
        }

        if (logger != null) {
            logger.warning("Could not find a source for MSPT on this server; /ping will show it as n/a.");
        }
        return MsptSource.NONE;
    }

    private interface Reader {
        OptionalDouble read() throws Exception;
    }

    private static MsptSource named(String name, Reader reader) {
        return new MsptSource() {
            @Override
            public OptionalDouble read() throws Exception {
                return reader.read();
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    private static boolean works(MsptSource source) {
        try {
            source.read();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}
