package net.vicemc.modules.regions;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A persistent polygon region. The footprint is a list of 2D vertices
 * (x, z) projected down a vertical column from minY to maxY; every block
 * whose x/z coordinate falls inside the polygon is part of the region.
 */
public final class RegionPolygon {

    private String id;
    private String name;
    private String world;
    private List<int[]> vertices;
    private int minY;
    private int maxY;
    private Map<String, Boolean> flags;

    public RegionPolygon() {
    }

    public RegionPolygon(String id, String name, String world, List<int[]> vertices,
                         int minY, int maxY, Map<String, Boolean> flags) {
        this.id = id;
        this.name = name;
        this.world = world;
        this.vertices = new ArrayList<>(vertices);
        this.minY = minY;
        this.maxY = maxY;
        this.flags = new LinkedHashMap<>(flags);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String world() {
        return world;
    }

    public List<int[]> vertices() {
        return vertices;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public Map<String, Boolean> flags() {
        return flags;
    }

    public boolean flag(RegionsFlag flag) {
        return flags.getOrDefault(flag.key(), flag.defaultValue());
    }

    public void setFlag(RegionsFlag flag, boolean value) {
        flags.put(flag.key(), value);
    }

    /**
     * Point-in-polygon ray casting: is the x/z column block part of the
     * footprint and is the y coordinate inside the vertical bounds?
     */
    public boolean contains(Location loc) {
        if (loc.getWorld() == null || !loc.getWorld().getName().equalsIgnoreCase(world)) {
            return false;
        }
        int y = loc.getBlockY();
        if (y < minY || y > maxY) {
            return false;
        }
        return contains2D(loc.getBlockX(), loc.getBlockZ());
    }

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
        int n = vertices.size();
        double sum = 0;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            int[] a = vertices.get(i);
            int[] b = vertices.get(j);
            sum += (double) a[0] * b[1] - (double) b[0] * a[1];
        }
        return Math.abs(sum) / 2.0;
    }
}
