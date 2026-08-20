package net.vicemc.modules.business;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.ViceModule;
import net.vicemc.core.ViceCore;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tracks how long employees spend inside their business's plot. The tick
 * mechanism (driven by {@code BusinessModule.onEnable()}) calls
 * {@link #tickAll()} every second. For each online player the service checks
 * whether they are standing inside a plot whose type matches a business they
 * own or are employed at. If so, their accumulated work time is incremented.
 * <p>
 * The Properties module is accessed via reflection since the business module
 * has no compile-time dependency on it.
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

    // Cached reflection handles
    private Method managerMethod;
    private Method atMethod;
    private Method ownedMethod;
    private boolean reflectionFailed = false;

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

            Object plot = getPlotAtBlock(block);
            if (plot == null || !isPlotOwned(plot)) {
                if (currentBusiness.containsKey(uuid)) {
                    stopTracking(player);
                }
                continue;
            }

            String plotType = getPlotType(plot);
            if (plotType == null) {
                if (currentBusiness.containsKey(uuid)) {
                    stopTracking(player);
                }
                continue;
            }

            Business matched = findMatchingBusiness(uuid, plotType);
            if (matched == null) {
                if (currentBusiness.containsKey(uuid)) {
                    stopTracking(player);
                }
                continue;
            }

            Integer trackedId = currentBusiness.get(uuid);
            if (trackedId == null || trackedId != matched.id) {
                startTracking(player, matched.id);
            }

            incrementWorkTime(matched.id, uuid, 1L);
        }

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
        ctx.storage().setModuleData("business",
                WORKTIME_PREFIX + businessId + ":" + playerUUID,
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

    private Business findMatchingBusiness(UUID playerUUID, String plotType) {
        for (Business business : manager.all()) {
            if (business.type.equalsIgnoreCase(plotType)
                    && manager.isOwnerOrEmployee(playerUUID, business)) {
                return business;
            }
        }
        return null;
    }

    private String cacheKey(int businessId, UUID playerUUID) {
        return businessId + ":" + playerUUID;
    }

    // --- Reflection: Properties module access ------------------------------

    /**
     * Resolves the PlotManager from the Properties module via reflection and
     * calls {@code at(Block)} to find the plot at the given block.
     */
    private Object getPlotAtBlock(Block block) {
        try {
            Object plotManager = resolvePlotManager();
            if (plotManager == null) {
                return null;
            }
            if (atMethod == null) {
                atMethod = plotManager.getClass().getMethod("at", Block.class);
            }
            return atMethod.invoke(plotManager, block);
        } catch (Exception e) {
            reflectionFailed = true;
            return null;
        }
    }

    /** Reads the {@code type} field from a Plot object via reflection. */
    private String getPlotType(Object plot) {
        try {
            var field = plot.getClass().getField("type");
            Object value = field.get(plot);
            return value instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Calls {@code owned()} on a Plot object via reflection. */
    private boolean isPlotOwned(Object plot) {
        try {
            if (ownedMethod == null) {
                ownedMethod = plot.getClass().getMethod("owned");
            }
            Object result = ownedMethod.invoke(plot);
            return result instanceof Boolean b && b;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolves the PlotManager from the Properties module via the module
     * registry. Uses reflection to call {@code manager()} on the module.
     */
    private Object resolvePlotManager() {
        if (reflectionFailed) {
            return null;
        }
        try {
            Optional<ViceModule> module = ViceCore.get().getModuleRegistry()
                    .module("properties");
            if (module.isEmpty()) {
                return null;
            }
            ViceModule propsModule = module.get();
            if (managerMethod == null) {
                managerMethod = propsModule.getClass().getMethod("manager");
            }
            return managerMethod.invoke(propsModule);
        } catch (Exception e) {
            reflectionFailed = true;
            return null;
        }
    }
}
