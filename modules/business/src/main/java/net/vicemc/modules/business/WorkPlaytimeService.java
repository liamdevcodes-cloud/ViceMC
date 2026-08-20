package net.vicemc.modules.business;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.core.ViceCore;
import net.vicemc.modules.properties.Plot;
import net.vicemc.modules.properties.PlotManager;
import net.vicemc.modules.properties.PropertiesModule;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks how long employees spend inside their business's plot. The tick
 * mechanism (driven by {@code BusinessModule.onEnable()}) calls
 * {@link #tickAll()} every second. For each online player the service checks
 * whether they are standing inside a plot whose type matches a business they
 * own or are employed at. If so, their accumulated work time is incremented.
 * <p>
 * Storage keys follow the pattern {@code worktime:<businessId>:<playerUuid>}
 * and store the seconds count as a plain string.
 */
public final class WorkPlaytimeService {

    private static final String WORKTIME_PREFIX = "worktime:";

    private final ViceModuleContext ctx;
    private final BusinessManager manager;

    /** Maps player UUID to the businessId they are currently working in. */
    private final Map<UUID, Integer> currentBusiness = new HashMap<>();

    /** In-memory work-time accumulator; flushed periodically to storage. */
    private final Map<String, Long> pendingSeconds = new HashMap<>();

    /** Tick counter — save to storage every 60 ticks (60 seconds). */
    private int ticksSinceSave = 0;

    public WorkPlaytimeService(ViceModuleContext ctx, BusinessManager manager) {
        this.ctx = ctx;
        this.manager = manager;
    }

    // --- Tracking API (called from events) ---------------------------------

    /**
     * Called when a player enters a business plot (e.g. on PlayerMoveEvent).
     * Stores the business ID they are working in.
     */
    public void startTracking(Player player, int businessId) {
        currentBusiness.put(player.getUniqueId(), businessId);
    }

    /** Called when a player leaves a business plot. */
    public void stopTracking(Player player) {
        currentBusiness.remove(player.getUniqueId());
    }

    // --- Tick (called every second by scheduler) ---------------------------

    /**
     * Called every second (20 ticks) by the Bukkit scheduler. For each online
     * player, checks whether they are inside a matching business plot and
     * increments their work time.
     */
    public void tickAll() {
        ticksSinceSave++;

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Block block = player.getLocation().getBlock();

            // Resolve the Properties PlotManager
            PlotManager plotManager = resolvePlotManager();
            if (plotManager == null) {
                continue;
            }

            Plot plot = plotManager.at(block);
            if (plot == null || !plot.owned()) {
                // Player is not inside any owned plot — stop tracking
                if (currentBusiness.containsKey(uuid)) {
                    stopTracking(player);
                }
                continue;
            }

            // Find a business whose type matches the plot type and where the
            // player is owner or employee
            Business matched = findMatchingBusiness(uuid, plot);
            if (matched == null) {
                // Plot exists but no matching business
                if (currentBusiness.containsKey(uuid)) {
                    stopTracking(player);
                }
                continue;
            }

            // Ensure tracking is active for this business
            Integer trackedId = currentBusiness.get(uuid);
            if (trackedId == null || trackedId != matched.id) {
                startTracking(player, matched.id);
            }

            // Increment work time by 1 second
            incrementWorkTime(matched.id, uuid, 1L);
        }

        // Periodic flush to storage
        if (ticksSinceSave >= 60) {
            flushPending();
            ticksSinceSave = 0;
        }
    }

    // --- Queries -----------------------------------------------------------

    /**
     * Returns accumulated work time in seconds for a player in a specific
     * business. Loads from storage if not already in memory.
     */
    public long getWorkSeconds(UUID playerUUID, int businessId) {
        String key = cacheKey(businessId, playerUUID);
        Long pending = pendingSeconds.get(key);
        if (pending != null) {
            return pending;
        }
        return loadWorkTime(businessId, playerUUID);
    }

    /** Returns the businessId a player is currently tracked in, or 0. */
    public int currentBusinessId(UUID playerUUID) {
        return currentBusiness.getOrDefault(playerUUID, 0);
    }

    /** Returns an unmodifiable snapshot of all tracked players. */
    public Map<UUID, Integer> trackedPlayers() {
        return Map.copyOf(currentBusiness);
    }

    /** Returns all work times for a given business. */
    public Map<UUID, Long> getAllWorkTimes(int businessId) {
        Map<UUID, Long> result = new HashMap<>();
        for (Business business : manager.byId(businessId).stream().toList()) {
            for (String uuidStr : business.roleAssignments.keySet()) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    result.put(uuid, getWorkSeconds(uuid, businessId));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return result;
    }

    // --- Mutation ----------------------------------------------------------

    /** Persists work time (used after salary payout to reset). */
    public void saveWorkTime(int businessId, UUID playerUUID, long seconds) {
        String key = cacheKey(businessId, playerUUID);
        pendingSeconds.put(key, seconds);
        ctx.storage().setModuleData("business", WORKTIME_PREFIX + businessId + ":" + playerUUID,
                String.valueOf(seconds));
    }

    /** Resets accumulated work time to zero. */
    public void resetWorkTime(int businessId, UUID playerUUID) {
        saveWorkTime(businessId, playerUUID, 0L);
    }

    // --- Internal helpers --------------------------------------------------

    private void incrementWorkTime(int businessId, UUID playerUUID, long delta) {
        String key = cacheKey(businessId, playerUUID);
        pendingSeconds.merge(key, delta, Long::sum);
    }

    private void flushPending() {
        for (Map.Entry<String, Long> entry : pendingSeconds.entrySet()) {
            String key = entry.getKey();
            // key format: "<businessId>:<playerUUID>"
            int sep = key.indexOf(':');
            int businessId = Integer.parseInt(key.substring(0, sep));
            UUID playerUUID = UUID.fromString(key.substring(sep + 1));
            ctx.storage().setModuleData("business",
                    WORKTIME_PREFIX + businessId + ":" + playerUUID,
                    String.valueOf(entry.getValue()));
        }
    }

    private long loadWorkTime(int businessId, UUID playerUUID) {
        return ctx.storage()
                .getModuleData("business", WORKTIME_PREFIX + businessId + ":" + playerUUID)
                .map(Long::parseLong)
                .orElse(0L);
    }

    /**
     * Finds the business owned or employed by the player whose type matches
     * the given plot type.
     */
    private Business findMatchingBusiness(UUID playerUUID, Plot plot) {
        String plotType = plot.type; // e.g. "SHOP"
        for (Business business : manager.all()) {
            if (business.type.equalsIgnoreCase(plotType)
                    && manager.isOwnerOrEmployee(playerUUID, business)) {
                return business;
            }
        }
        return null;
    }

    private PlotManager resolvePlotManager() {
        return ViceCore.get().getModuleRegistry()
                .module("properties")
                .map(m -> ((PropertiesModule) m).manager())
                .orElse(null);
    }

    private String cacheKey(int businessId, UUID playerUUID) {
        return businessId + ":" + playerUUID;
    }
}
