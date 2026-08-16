package net.vicemc.modules.business;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A player-owned business with employees and an optional government stock
 * inventory (used by dealerships).
 */
public final class Business {

    public int id;
    public String type;
    public String name;
    public UUID owner;
    public final Set<UUID> employees = new HashSet<>();
    public boolean licensed;
    public long createdAt;
    public final Map<String, Integer> stock = new HashMap<>();

    public BusinessType typeEnum() {
        try {
            return BusinessType.valueOf(type);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public int stockOf(String key) {
        return stock.getOrDefault(key, 0);
    }
}
