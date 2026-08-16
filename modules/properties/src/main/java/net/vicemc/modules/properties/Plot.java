package net.vicemc.modules.properties;

import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A selectable cuboid plot. Plots are empty shells when bought; owners can
 * place furniture, and every placed block is tracked per-placer so players can
 * only ever modify their own interior, never the structure that was already
 * there. {@code owner == null} means the plot is unowned and available on the
 * market at {@link #price}.
 */
public final class Plot {

    public String serial = "";
    public String type = PlotType.HOUSE.name();
    public String world = "world";
    public int minX, minY, minZ, maxX, maxY, maxZ;

    /** Base value assigned by the admin when the plot is created. */
    public double price;

    public String owner;
    public long boughtAt;

    /** Member UUIDs allowed to build and use the plot. */
    public List<String> members = new ArrayList<>();

    public boolean forSale;
    public double salePrice;
    /** Selling with the interior included requires a real estate permit. */
    public boolean sellWithInterior;
    /** Snapshot of the interior taken when listing for sale (anti-scam). */
    public String interiorManifest = "";

    public boolean forRent;
    public double rentPrice;
    public long rentDays;
    public String tenant;
    public long rentUntil;

    /** blockKey (x,y,z) -> UUID of the player who placed the block. */
    public Map<String, String> placed = new HashMap<>();

    public boolean owned() {
        return owner != null;
    }

    public PlotType type() {
        PlotType t = PlotType.from(type);
        return t == null ? PlotType.HOUSE : t;
    }

    public boolean contains(Block block) {
        return contains(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return worldName != null
                && worldName.equalsIgnoreCase(world)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /** True while the plot is listed for sale WITH its interior (locked). */
    public boolean interiorLocked() {
        return forSale && sellWithInterior;
    }

    public boolean isRented() {
        return tenant != null && rentUntil > System.currentTimeMillis();
    }

    public UUID ownerUuid() {
        return owner == null ? null : UUID.fromString(owner);
    }

    public World bukkitWorld() {
        return world == null ? null : org.bukkit.Bukkit.getWorld(world);
    }
}
