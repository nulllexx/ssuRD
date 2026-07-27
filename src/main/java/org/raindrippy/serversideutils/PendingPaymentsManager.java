package org.raindrippy.serversideutils;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class PendingPaymentsManager {

    private static final String SEPARATOR = ";";

    // A single "you were paid while offline" notice
    public static final class PendingPayment {
        private final String from;
        private final double amount;

        public PendingPayment(String from, double amount) {
            this.from = from;
            this.amount = amount;
        }

        public String getFrom() {
            return from;
        }

        public double getAmount() {
            return amount;
        }
    }

    private final JavaPlugin plugin;
    private File paymentsFile;
    private FileConfiguration paymentsConfig;
    private final Map<UUID, List<PendingPayment>> payments = new HashMap<>();

    public PendingPaymentsManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void setup() {
        paymentsFile = new File(plugin.getDataFolder(), "pending_payments.yml");
        if (!paymentsFile.exists()) {
            paymentsFile.getParentFile().mkdirs();
            try {
                paymentsFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create pending_payments.yml!", e);
            }
        }
        paymentsConfig = YamlConfiguration.loadConfiguration(paymentsFile);
    }

    public void load() {
        payments.clear();
        for (String uuidString : paymentsConfig.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidString);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping malformed UUID key in pending_payments.yml: " + uuidString);
                continue;
            }
            List<PendingPayment> decoded = new ArrayList<>();
            for (String entry : paymentsConfig.getStringList(uuidString)) {
                PendingPayment payment = decode(entry);
                if (payment == null) {
                    plugin.getLogger().warning("Skipping malformed pending payment for " + uuidString + ": " + entry);
                    continue;
                }
                decoded.add(payment);
            }
            if (!decoded.isEmpty()) {
                payments.put(uuid, decoded);
            }
        }
    }

    public void save() {
        // Drop entries removed from the map so delivered notices don't resurrect on reload.
        for (String key : new HashSet<>(paymentsConfig.getKeys(false))) {
            paymentsConfig.set(key, null);
        }
        for (Map.Entry<UUID, List<PendingPayment>> entry : payments.entrySet()) {
            List<String> encoded = new ArrayList<>();
            for (PendingPayment payment : entry.getValue()) {
                encoded.add(encode(payment));
            }
            paymentsConfig.set(entry.getKey().toString(), encoded);
        }
        try {
            paymentsConfig.save(paymentsFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save pending_payments.yml!", e);
        }
    }

    public void record(UUID recipient, String senderName, double amount) {
        payments.computeIfAbsent(recipient, key -> new ArrayList<>())
                .add(new PendingPayment(senderName, amount));
        save();
    }

    public boolean hasPending(UUID recipient) {
        return payments.containsKey(recipient);
    }

    /** Returns the undelivered notices for a player, oldest first; never null. */
    public List<PendingPayment> getPending(UUID recipient) {
        List<PendingPayment> pending = payments.get(recipient);
        if (pending == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(pending));
    }

    public void clearPending(UUID recipient) {
        if (payments.remove(recipient) != null) {
            save();
        }
    }

    private static String encode(PendingPayment payment) {
        return payment.getFrom() + SEPARATOR + payment.getAmount();
    }

    /** Parses one encoded entry, returning null if it is unusable rather than failing the load. */
    private static PendingPayment decode(String entry) {
        if (entry == null) {
            return null;
        }
        // lastIndexOf so a separator that somehow ended up in the name can't eat the amount.
        int split = entry.lastIndexOf(SEPARATOR);
        if (split <= 0 || split == entry.length() - 1) {
            return null;
        }
        double amount;
        try {
            amount = Double.parseDouble(entry.substring(split + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (!Double.isFinite(amount) || amount <= 0) {
            return null;
        }
        return new PendingPayment(entry.substring(0, split), amount);
    }
}
