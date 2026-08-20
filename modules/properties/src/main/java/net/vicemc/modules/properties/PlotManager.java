package net.vicemc.modules.properties;

import com.google.gson.reflect.TypeToken;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.Json;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Registry of all plots. Each plot is persisted as its own JSON blob under
 * module data so a single plot never rewrites the whole set. Serial numbers
 * are postcode-style (001AA, 002AA ... 099AA, 001AB, ...) and never reused.
 */
public final class PlotManager {

    private static final String PREFIX = "plot:";
    private static final String NEXT_PREFIX = "meta:next:";
    private static final String PERMIT_PREFIX = "permit:";

    private static final TypeToken<List<LegacyProperty>> LEGACY_TYPE = new TypeToken<List<LegacyProperty>>() {
    };

    private final ViceModuleContext ctx;
    private final net.vicemc.api.util.YamlConfig config;
    private final Map<String, Plot> plots = new LinkedHashMap<>();

    public PlotManager(ViceModuleContext ctx, net.vicemc.api.util.YamlConfig config) {
        this.ctx = ctx;
        this.config = config;
        load();
        migrateLegacy();
    }

    // --- Persistence ------------------------------------------------------

    private void load() {
        for (Map.Entry<String, String> entry : ctx.storage().moduleDataAll("properties").entrySet()) {
            if (!entry.getKey().startsWith(PREFIX)) {
                continue;
            }
            try {
                Plot plot = Json.fromJson(entry.getValue(), Plot.class);
                if (plot != null && plot.serial != null && !plot.serial.isEmpty()) {
                    plots.put(plot.serial, plot);
                }
            } catch (Exception ignored) {
            }
        }
    }

    public void save(Plot plot) {
        ctx.storage().setModuleData("properties", PREFIX + plot.serial, Json.toJson(plot));
    }

    private void deleteData(Plot plot) {
        ctx.storage().removeModuleData("properties", PREFIX + plot.serial);
    }

    /** One-time conversion of the old single-home market into house plots. */
    private void migrateLegacy() {
        if (!plots.isEmpty()) {
            return;
        }
        Optional<String> legacy = ctx.storage().getModuleData("properties", "properties");
        if (legacy.isEmpty()) {
            return;
        }
        try {
            List<LegacyProperty> old = Json.fromJson(legacy.get(), LEGACY_TYPE.getType());
            if (old == null) {
                return;
            }
            int migrated = 0;
            for (LegacyProperty lp : old) {
                Plot plot = new Plot();
                plot.serial = nextSerial(PlotType.HOUSE);
                plot.type = PlotType.HOUSE.name();
                plot.world = lp.world == null ? "world" : lp.world;
                int x = (int) Math.floor(lp.x);
                int y = (int) Math.floor(lp.y);
                int z = (int) Math.floor(lp.z);
                plot.minX = x;
                plot.maxX = x;
                plot.minY = Math.max(0, y - 3);
                plot.maxY = y + 3;
                plot.minZ = z;
                plot.maxZ = z;
                plot.price = lp.price;
                plot.owner = lp.owner;
                plot.boughtAt = lp.boughtAt;
                plots.put(plot.serial, plot);
                save(plot);
                migrated++;
            }
            ctx.storage().removeModuleData("properties", "properties");
            ctx.logger().info("Migrated " + migrated + " legacy property(ies) into plots.");
        } catch (Exception ex) {
            ctx.logger().warning("Legacy property migration failed: " + ex.getMessage());
        }
    }

    // --- Serial numbers ---------------------------------------------------

    public String nextSerial(PlotType type) {
        String key = NEXT_PREFIX + type.name();
        int counter = ctx.storage().getModuleData("properties", key).map(Integer::parseInt).orElse(0) + 1;
        ctx.storage().setModuleData("properties", key, String.valueOf(counter));
        return serialFromIndex(counter);
    }

    static String serialFromIndex(int index) {
        int k = index - 1;
        int num = k % 99 + 1;
        int suffix = k / 99;
        char a = (char) ('A' + suffix / 26);
        char b = (char) ('A' + suffix % 26);
        return String.format("%03d%c%c", num, a, b);
    }

    // --- Creation / removal ----------------------------------------------

    public Plot create(PlotType type, String world, Location a, Location b, double value) {
        Plot plot = new Plot();
        plot.serial = nextSerial(type);
        plot.type = type.name();
        plot.world = world;
        plot.minX = Math.min(a.getBlockX(), b.getBlockX());
        plot.maxX = Math.max(a.getBlockX(), b.getBlockX());
        plot.minY = Math.min(a.getBlockY(), b.getBlockY());
        plot.maxY = Math.max(a.getBlockY(), b.getBlockY());
        plot.minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        plot.maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
        plot.price = value;
        if (overlaps(plot)) {
            return null;
        }
        plots.put(plot.serial, plot);
        save(plot);
        return plot;
    }

    /**
     * Creates a polygon plot from a list of 2D [x,z] vertices and a Y height.
     * The bounding box is derived from the vertices. The vertices' Y is taken
     * from the first vertex and the height is added on top.
     */
    public Plot createPolygon(PlotType type, String world, List<int[]> vertices, int baseY, int height, double value) {
        Plot plot = new Plot();
        plot.serial = nextSerial(type);
        plot.type = type.name();
        plot.world = world;
        plot.vertices = new ArrayList<>(vertices);
        plot.minY = baseY;
        plot.maxY = baseY + height;

        // Derive bounding box from vertices
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (int[] v : vertices) {
            if (v[0] < minX) minX = v[0];
            if (v[0] > maxX) maxX = v[0];
            if (v[1] < minZ) minZ = v[1];
            if (v[1] > maxZ) maxZ = v[1];
        }
        plot.minX = minX;
        plot.maxX = maxX;
        plot.minZ = minZ;
        plot.maxZ = maxZ;
        plot.price = value;
        if (overlaps(plot)) {
            return null;
        }
        plots.put(plot.serial, plot);
        save(plot);
        return plot;
    }

    public boolean overlaps(Plot candidate) {
        for (Plot other : plots.values()) {
            if (other.serial.equals(candidate.serial)) {
                continue;
            }
            if (!other.world.equalsIgnoreCase(candidate.world)) {
                continue;
            }
            if (candidate.minX <= other.maxX && candidate.maxX >= other.minX
                    && candidate.minY <= other.maxY && candidate.maxY >= other.minY
                    && candidate.minZ <= other.maxZ && candidate.maxZ >= other.minZ) {
                return true;
            }
        }
        return false;
    }

    /** Removes the plot from the registry and storage without touching blocks. */
    public void remove(Plot plot) {
        plots.remove(plot.serial);
        deleteData(plot);
    }

    // --- Queries ----------------------------------------------------------

    public Plot bySerial(String serial) {
        return serial == null ? null : plots.get(serial.toUpperCase());
    }

    public List<Plot> all() {
        List<Plot> list = new ArrayList<>(plots.values());
        list.sort((p1, p2) -> p1.serial.compareTo(p2.serial));
        return list;
    }

    public List<Plot> byType(PlotType type) {
        return all().stream().filter(p -> p.type().equals(type)).toList();
    }

    /** Plots a buyer can buy outright: unowned shells plus listed resales. */
    public List<Plot> market(PlotType type) {
        return all().stream().filter(p -> p.type().equals(type)
                && (p.owner == null || p.forSale)).toList();
    }

    public List<Plot> forRent(PlotType type) {
        return all().stream().filter(p -> p.type().equals(type) && p.forRent && !p.isRented()).toList();
    }

    /** Plots this player is currently renting (not yet expired). */
    public List<Plot> rentedBy(UUID uuid) {
        String id = uuid.toString();
        return all().stream().filter(p -> id.equals(p.tenant) && p.isRented()).toList();
    }

    /** Plots where this player is an added member (not the owner). */
    public List<Plot> memberOf(UUID uuid) {
        String id = uuid.toString();
        return all().stream().filter(p -> p.members.contains(id)).toList();
    }

    public List<Plot> ownedBy(UUID uuid) {
        String id = uuid.toString();
        return all().stream().filter(p -> id.equals(p.owner)).toList();
    }

    public boolean ownsAny(UUID uuid) {
        return !ownedBy(uuid).isEmpty();
    }

    public int ownedCountByType(UUID uuid, PlotType type) {
        String id = uuid.toString();
        return (int) all().stream().filter(p -> p.type().equals(type) && id.equals(p.owner)).count();
    }

    /** The plot containing the block, if any. */
    public Plot at(Block block) {
        for (Plot plot : plots.values()) {
            if (plot.contains(block)) {
                return plot;
            }
        }
        return null;
    }

    public boolean canAccess(Plot plot, UUID uuid) {
        if (uuid == null) {
            return false;
        }
        String id = uuid.toString();
        return id.equals(plot.owner) || plot.members.contains(id) || id.equals(plot.tenant);
    }

    // --- Limits & permits -------------------------------------------------

    /** The current default plot type the admin wand creates. */
    public PlotType wandType() {
        PlotType type = PlotType.from(config.getString("wand.type", "HOUSE"));
        return type == null ? PlotType.HOUSE : type;
    }

    /** The current default value the admin wand assigns to new plots. */
    public double wandValue() {
        return Math.max(0, config.getDouble("wand.value", 100000));
    }

    public int limit(PlotType type, UUID uuid) {
        String key = type.name().toLowerCase();
        int base = Math.max(0, config.getInt("limits." + key, defaultLimit(type)));
        int permitted = Math.max(base, config.getInt("permit-limits." + key, base));
        return hasPermit(uuid) ? permitted : base;
    }

    private int defaultLimit(PlotType type) {
        return switch (type) {
            case HOUSE -> 1;
            case APARTMENT -> 2;
            case FARM, MINE, SHOP, FOOD_COMPANY, FACTORY, DEALERSHIP, JEWELRY_STORE, BANK -> 1;
        };
    }

    public boolean hasPermit(UUID uuid) {
        return "true".equalsIgnoreCase(
                ctx.storage().getModuleData("properties", PERMIT_PREFIX + uuid).orElse("false"));
    }

    public void setPermit(UUID uuid, boolean on) {
        ctx.storage().setModuleData("properties", PERMIT_PREFIX + uuid, String.valueOf(on));
    }

    // --- Ownership changes ------------------------------------------------

    /** Completes a sale. Interior sales keep blocks; plain sales reset the plot. */
    public void finalizeSale(Plot plot, UUID newOwner, boolean withInterior, boolean dropItems) {
        if (!withInterior) {
            clearPlot(plot, dropItems);
        } else {
            reassignPlaced(plot, newOwner);
        }
        plot.owner = newOwner.toString();
        plot.boughtAt = System.currentTimeMillis();
        plot.members.clear();
        plot.forSale = false;
        plot.salePrice = 0;
        plot.sellWithInterior = false;
        plot.forRent = false;
        plot.tenant = null;
        plot.rentUntil = 0;
        plot.interiorManifest = "";
        save(plot);
    }

    /** Sells a plot back to the market as an empty shell. */
    public void sellBack(Plot plot, boolean dropItems) {
        clearPlot(plot, dropItems);
        plot.owner = null;
        plot.boughtAt = 0;
        plot.members.clear();
        plot.forSale = false;
        plot.salePrice = 0;
        plot.sellWithInterior = false;
        plot.forRent = false;
        plot.tenant = null;
        plot.rentUntil = 0;
        plot.interiorManifest = "";
        save(plot);
    }

    /** Admin transfer of a deed to another player (no money involved). */
    public void transfer(Plot plot, UUID newOwner) {
        plot.owner = newOwner.toString();
        plot.boughtAt = System.currentTimeMillis();
        plot.members.clear();
        plot.forSale = false;
        plot.salePrice = 0;
        plot.sellWithInterior = false;
        plot.forRent = false;
        plot.tenant = null;
        plot.rentUntil = 0;
        plot.interiorManifest = "";
        save(plot);
    }

    public void rent(Plot plot, UUID tenant, long untilMs) {
        plot.tenant = tenant.toString();
        plot.rentUntil = untilMs;
        save(plot);
    }

    public void cancelRent(Plot plot, boolean dropItems) {
        clearPlacedBy(plot, plot.tenant, dropItems);
        plot.tenant = null;
        plot.rentUntil = 0;
        plot.forRent = false;
        save(plot);
    }

    /** Expires finished rentals; the tenant's own furniture is cleared. */
    public void expireTenants(boolean dropItems) {
        long now = System.currentTimeMillis();
        for (Plot plot : plots.values()) {
            if (plot.tenant == null || plot.rentUntil <= 0 || plot.rentUntil > now) {
                continue;
            }
            String tenantId = plot.tenant;
            clearPlacedBy(plot, tenantId, dropItems);
            plot.tenant = null;
            plot.rentUntil = 0;
            plot.forRent = false;
            save(plot);
            notifyOwner(plot, "&eYour rental &f" + plot.serial + "&e expired. The tenant's furniture was removed.");
            Player tenant = Bukkit.getPlayer(tenantId);
            if (tenant != null) {
                ctx.notifications().warn(tenant, "&cYour rental of &f" + plot.serial + "&c expired.");
            }
        }
    }

    private void notifyOwner(Plot plot, String message) {
        if (plot.owner == null) {
            return;
        }
        Player owner = Bukkit.getPlayer(plot.owner);
        if (owner != null) {
            ctx.notifications().msg(owner, message);
        }
    }

    // --- Placed blocks ----------------------------------------------------

    public static String blockKey(Block block) {
        return block.getX() + "," + block.getY() + "," + block.getZ();
    }

    public boolean isPlaced(Plot plot, Block block) {
        return plot.placed.containsKey(blockKey(block));
    }

    public void recordPlaced(Plot plot, Block block, UUID placer) {
        plot.placed.put(blockKey(block), placer.toString());
        save(plot);
    }

    public void unrecordPlaced(Plot plot, Block block) {
        plot.placed.remove(blockKey(block));
        save(plot);
    }

    /** Removes every block placed by the given player (furniture they placed). */
    public void clearPlacedBy(Plot plot, String placerId, boolean dropItems) {
        for (String key : new ArrayList<>(plot.placed.keySet())) {
            if (!placerId.equals(plot.placed.get(key))) {
                continue;
            }
            Block block = blockAt(plot, key);
            if (block != null) {
                dropContents(block, dropItems);
                block.setType(Material.AIR, false);
            }
            plot.placed.remove(key);
        }
        save(plot);
    }

    /** Removes every placed block in the plot. */
    public void clearPlot(Plot plot, boolean dropItems) {
        for (String key : new ArrayList<>(plot.placed.keySet())) {
            Block block = blockAt(plot, key);
            if (block != null) {
                dropContents(block, dropItems);
                block.setType(Material.AIR, false);
            }
            plot.placed.remove(key);
        }
        save(plot);
    }

    private void dropContents(Block block, boolean dropItems) {
        if (!dropItems) {
            return;
        }
        BlockState state = block.getState();
        if (state instanceof InventoryHolder holder) {
            for (ItemStack item : holder.getInventory().getContents()) {
                if (item != null) {
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), item);
                }
            }
            holder.getInventory().clear();
        }
    }

    private void reassignPlaced(Plot plot, UUID newOwner) {
        String id = newOwner.toString();
        for (String key : plot.placed.keySet()) {
            plot.placed.put(key, id);
        }
    }

    private Block blockAt(Plot plot, String key) {
        String[] parts = key.split(",");
        if (parts.length != 3) {
            return null;
        }
        World world = Bukkit.getWorld(plot.world);
        if (world == null) {
            return null;
        }
        try {
            return world.getBlockAt(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // --- Interior manifest ------------------------------------------------

    /**
     * Builds a readable snapshot of the interior (furniture blocks + stored
     * items) so buyers see exactly what is included before purchasing.
     */
    public String buildManifest(Plot plot) {
        Map<Material, Integer> counts = new HashMap<>();
        long storedItems = 0;
        for (String key : plot.placed.keySet()) {
            Block block = blockAt(plot, key);
            if (block == null || block.getType().isAir()) {
                continue;
            }
            counts.merge(block.getType(), 1, Integer::sum);
            BlockState state = block.getState();
            if (state instanceof InventoryHolder holder) {
                for (ItemStack item : holder.getInventory().getContents()) {
                    if (item != null) {
                        storedItems += item.getAmount();
                    }
                }
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("&7Furniture blocks: &f" + plot.placed.size());
        counts.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(8)
                .forEach(e -> lines.add("&8 - &f" + e.getValue() + "x &7" + friendly(e.getKey())));
        lines.add("&7Items stored: &f" + storedItems);
        return String.join("\n", lines);
    }

    private String friendly(Material material) {
        return material.name().toLowerCase().replace('_', ' ');
    }

    /** Legacy home data model from Vice Properties 1.x. */
    private static final class LegacyProperty {
        public int id;
        public String name = "";
        public double price;
        public String world = "world";
        public double x, y, z;
        public float yaw, pitch;
        public String owner;
        public long boughtAt;
    }
}
