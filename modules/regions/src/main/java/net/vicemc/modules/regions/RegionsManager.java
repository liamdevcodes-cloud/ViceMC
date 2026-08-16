package net.vicemc.modules.regions;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.Json;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the persistent polygon regions, the in-progress wand selections and
 * the per-player current-zone cache used by the chat safety indicator. All
 * access is safe from any region thread: the region map is a concurrent map
 * plus an insertion-ordered id list, and regions are immutable after load.
 */
public final class RegionsManager {

    private static final String STORE_KEY = "region:";

    private final ViceModuleContext ctx;
    private final ConcurrentHashMap<String, RegionPolygon> regions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();
    private final Map<UUID, List<Location>> selections = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerZone = new ConcurrentHashMap<>();

    public RegionsManager(ViceModuleContext ctx) {
        this.ctx = ctx;
        load();
    }

    private void load() {
        List<String> keys = new ArrayList<>(ctx.storage().moduleDataAll("regions").keySet());
        keys.sort(Comparator.naturalOrder());
        for (String key : keys) {
            if (!key.startsWith(STORE_KEY)) {
                continue;
            }
            try {
                RegionPolygon region = Json.fromJson(ctx.storage().moduleDataAll("regions").get(key),
                        RegionPolygon.class);
                if (region == null || region.id() == null || region.vertices() == null
                        || region.vertices().size() < 3) {
                    continue;
                }
                regions.put(region.id().toLowerCase(), region);
                order.add(region.id().toLowerCase());
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void save(RegionPolygon region) {
        ctx.storage().setModuleData("regions", STORE_KEY + region.id(), Json.toJson(region));
    }

    // --- Queries ----------------------------------------------------------

    public List<RegionPolygon> all() {
        List<RegionPolygon> result = new ArrayList<>();
        for (String id : order) {
            RegionPolygon region = regions.get(id);
            if (region != null) {
                result.add(region);
            }
        }
        return result;
    }

    public RegionPolygon byId(String id) {
        return id == null ? null : regions.get(id.toLowerCase());
    }

    /** First region (by creation order) that contains the location, or null. */
    public RegionPolygon at(Location location) {
        for (String id : order) {
            RegionPolygon region = regions.get(id);
            if (region != null && region.contains(location)) {
                return region;
            }
        }
        return null;
    }

    // --- CRUD -------------------------------------------------------------

    public record CreateResult(RegionPolygon region, String error) {
    }

    public CreateResult create(String id, String name, String world, List<int[]> vertices,
                               int minY, int maxY) {
        if (id == null || !id.matches("[a-z0-9_-]{1,32}")) {
            return new CreateResult(null,
                    "Region id must be 1-32 lowercase letters, digits, '-' or '_'.");
        }
        String key = id.toLowerCase();
        if (regions.containsKey(key)) {
            return new CreateResult(null, "A region with that id already exists.");
        }
        if (vertices == null || vertices.size() < 3) {
            return new CreateResult(null, "You need at least 3 vertices.");
        }
        RegionPolygon region = new RegionPolygon(key,
                name == null || name.isEmpty() ? id : name, world, vertices, minY, maxY,
                RegionsFlag.defaults());
        regions.put(key, region);
        order.add(key);
        save(region);
        return new CreateResult(region, null);
    }

    public boolean delete(String id) {
        String key = id == null ? null : id.toLowerCase();
        if (key == null || regions.remove(key) == null) {
            return false;
        }
        order.remove(key);
        ctx.storage().removeModuleData("regions", STORE_KEY + key);
        return true;
    }

    public void rename(RegionPolygon region, String name) {
        region.setName(name == null || name.isEmpty() ? region.id() : name);
        save(region);
    }

    public void setFlag(RegionPolygon region, RegionsFlag flag, boolean value) {
        region.setFlag(flag, value);
        save(region);
    }

    // --- Safety -----------------------------------------------------------

    public Safety safety(RegionPolygon region) {
        boolean pvp = region.flag(RegionsFlag.PVP);
        boolean loot = region.flag(RegionsFlag.LOOT_DROP);
        if (pvp && loot) {
            return Safety.DANGER;
        }
        if (!pvp && !loot) {
            return Safety.SAFE;
        }
        return Safety.NEUTRAL;
    }

    // --- Wand selections --------------------------------------------------

    public List<Location> selection(UUID player) {
        return selections.computeIfAbsent(player, k -> new CopyOnWriteArrayList<>());
    }

    public void clearSelection(UUID player) {
        selections.remove(player);
    }

    // --- Zone cache (updated on the player's own thread) ------------------

    public void setZone(UUID player, String regionId) {
        if (regionId == null || regionId.isEmpty()) {
            playerZone.remove(player);
        } else {
            playerZone.put(player, regionId);
        }
    }

    public String zoneId(UUID player) {
        return playerZone.getOrDefault(player, "");
    }

    public RegionPolygon zoneRegion(UUID player) {
        String id = zoneId(player);
        return id.isEmpty() ? null : regions.get(id);
    }
}
