package net.vicemc.api.model;

import org.bukkit.Location;

import java.util.HashSet;
import java.util.Set;

/**
 * An axis-aligned cuboid region with tags. Used for gang territories,
 * the blood diamond mine, heist locations, and any other zone.
 */
public final class Region {

    private final String id;
    private final String world;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final Set<String> tags;

    public Region(String id, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Set<String> tags) {
        this.id = id;
        this.world = world;
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
        this.tags = new HashSet<>(tags);
    }

    public String id() {
        return id;
    }

    public String world() {
        return world;
    }

    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }

    public boolean contains(Location loc) {
        if (loc.getWorld() == null || !loc.getWorld().getName().equalsIgnoreCase(world)) {
            return false;
        }
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    public Set<String> tags() {
        return new HashSet<>(tags);
    }

    public String serialized() {
        return world + ":" + minX + "," + minY + "," + minZ + ":" + maxX + "," + maxY + "," + maxZ;
    }
}
