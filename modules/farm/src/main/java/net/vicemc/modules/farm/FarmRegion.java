package net.vicemc.modules.farm;

import org.bukkit.block.Block;

import java.util.UUID;

/**
 * A selectable cuboid farm region bound to one crop type. Crops planted inside
 * regrow after harvesting. The region is either public (no owner, no tax) or
 * owned by a player who takes a configurable margin of the sale value from
 * the players farming on their land.
 */
public final class FarmRegion {

    public String id = "";
    public String crop = "WHEAT";
    public String world = "world";
    public int minX, minY, minZ, maxX, maxY, maxZ;

    /** UUID of the owning player, or null for a public farm. */
    public String owner;

    /** Share (0.01 - 0.10) of the sale value the owner keeps from farmers. */
    public double margin = 0.02;

    public boolean contains(Block block) {
        if (block.getWorld() == null || !block.getWorld().getName().equalsIgnoreCase(world)) {
            return false;
        }
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public UUID ownerUuid() {
        return owner == null ? null : UUID.fromString(owner);
    }

    public boolean isPublic() {
        return owner == null;
    }
}
