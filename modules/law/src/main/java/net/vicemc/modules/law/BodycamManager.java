package net.vicemc.modules.law;

import com.google.gson.reflect.TypeToken;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.Json;
import net.vicemc.api.util.YamlConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mandatory bodycam recording for police interactions. Footage is retained
 * for 48 real-life hours and is the evidence basis for lawyer reviews.
 */
public final class BodycamManager {

    private static final TypeToken<List<BodycamEntry>> ENTRIES_TYPE = new TypeToken<List<BodycamEntry>>() {
    };

    private final ViceModuleContext ctx;
    private final boolean enabled;
    private final long retentionMillis;
    private final Map<UUID, List<BodycamEntry>> logs = new ConcurrentHashMap<>();

    public BodycamManager(ViceModuleContext ctx, YamlConfig config) {
        this.ctx = ctx;
        this.enabled = config.getBoolean("bodycam.enabled", true);
        this.retentionMillis = config.getInt("bodycam.retention-hours", 48) * 3600 * 1000L;
        for (Map.Entry<String, String> entry : ctx.storage().moduleDataAll("law").entrySet()) {
            if (!entry.getKey().startsWith("bodycam:")) {
                continue;
            }
            String[] parts = entry.getKey().split(":", 2);
            if (parts.length < 2) {
                continue;
            }
            UUID officer = UUID.fromString(parts[1]);
            List<BodycamEntry> list = Json.fromJson(entry.getValue(), ENTRIES_TYPE.getType());
            if (list != null) {
                logs.put(officer, new CopyOnWriteArrayList<>(list));
            }
        }
    }

    public void record(UUID officer, UUID target, String action, String charge) {
        if (!enabled) {
            return;
        }
        List<BodycamEntry> list = logs.computeIfAbsent(officer, k -> new CopyOnWriteArrayList<>());
        BodycamEntry entry = new BodycamEntry();
        entry.timestamp = System.currentTimeMillis();
        entry.officer = officer;
        entry.target = target;
        entry.action = action;
        entry.charge = charge == null ? "" : charge;
        list.add(entry);
        prune(list);
        save(officer);
    }

    private void prune(List<BodycamEntry> list) {
        long cutoff = System.currentTimeMillis() - retentionMillis;
        list.removeIf(e -> e.timestamp < cutoff);
    }

    private void save(UUID officer) {
        ctx.storage().setModuleData("law", "bodycam:" + officer, Json.toJson(logs.get(officer)));
    }

    /**
     * All footage involving the target within the retention window, newest
     * first. Used by lawyers to evaluate evidence.
     */
    public List<BodycamEntry> forTarget(UUID target) {
        long cutoff = System.currentTimeMillis() - retentionMillis;
        return logs.values().stream()
                .flatMap(List::stream)
                .filter(e -> e.target.equals(target) && e.timestamp >= cutoff)
                .sorted(Comparator.comparingLong((BodycamEntry e) -> e.timestamp).reversed())
                .toList();
    }

    public List<BodycamEntry> forOfficer(UUID officer, int hours) {
        long cutoff = System.currentTimeMillis() - Math.max(0, hours) * 3600 * 1000L;
        return logs.getOrDefault(officer, List.of()).stream()
                .filter(e -> e.timestamp >= cutoff)
                .sorted(Comparator.comparingLong((BodycamEntry e) -> e.timestamp).reversed())
                .toList();
    }
}
