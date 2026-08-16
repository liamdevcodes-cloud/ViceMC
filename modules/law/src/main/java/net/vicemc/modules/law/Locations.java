package net.vicemc.modules.law;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Location parsing helper for "world,x,y,z[,yaw,pitch]" strings.
 */
final class Locations {

    private Locations() {
    }

    static Location parse(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        double x = Double.parseDouble(parts[1]);
        double y = Double.parseDouble(parts[2]);
        double z = Double.parseDouble(parts[3]);
        float yaw = parts.length > 4 ? Float.parseFloat(parts[4]) : 0f;
        float pitch = parts.length > 5 ? Float.parseFloat(parts[5]) : 0f;
        return new Location(world, x, y, z, yaw, pitch);
    }
}
