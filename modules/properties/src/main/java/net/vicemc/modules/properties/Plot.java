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
    public String name = "";
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

    /**
     * Polygon vertices as [x, z] pairs. Null or empty means this plot uses
     * the legacy cuboid bounds (minX/minZ/maxX/maxZ). When present the plot
     * footprint is the polygon projected from minY to maxY.
     */
    public List<int[]> vertices;

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
        if (worldName == null || !worldName.equalsIgnoreCase(world)) {
            return false;
        }
        if (y < minY || y > maxY) {
            return false;
        }
        if (hasPolygon()) {
            return contains2D(x, z);
        }
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /** True when this plot was created with the polygon wand. */
    public boolean hasPolygon() {
        return vertices != null && vertices.size() >= 3;
    }

    /** Ray-casting point-in-polygon for the x/z footprint. */
    public boolean contains2D(int x, int z) {
        boolean inside = false;
        int n = vertices.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            int[] a = vertices.get(i);
            int[] b = vertices.get(j);
            if ((a[1] > z) != (b[1] > z)
                    && x < (double) (b[0] - a[0]) * (z - a[1]) / (double) (b[1] - a[1]) + a[0]) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Shoelace formula footprint area in blocks^2. */
    public double footprintArea() {
        if (!hasPolygon()) {
            return (double) (maxX - minX + 1) * (maxZ - minZ + 1);
        }
        int n = vertices.size();
        double sum = 0;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            int[] a = vertices.get(i);
            int[] b = vertices.get(j);
            sum += (double) a[0] * b[1] - (double) b[0] * a[1];
        }
        return Math.abs(sum) / 2.0;
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
