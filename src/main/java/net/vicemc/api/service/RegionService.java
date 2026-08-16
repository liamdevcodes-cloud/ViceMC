package net.vicemc.api.service;

import net.vicemc.api.model.Region;
import org.bukkit.Location;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Persistent tag-based cuboid regions. Used by gang territories, the blood
 * diamond mine, heist locations and similar zones.
 */
public interface RegionService {

    Region create(String id, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Set<String> tags);

    void delete(String id);

    Optional<Region> byId(String id);

    boolean isInside(Location location, String tag);

    Set<Region> at(Location location);

    Set<Region> byTag(String tag);

    Collection<Region> all();
}
