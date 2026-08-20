package net.vicemc.modules.business;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A player-owned business with employees, custom roles, salary configuration
 * and an optional government stock inventory (used by dealerships).
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

    /** Role definitions for this business: role name -> role config. */
    public final Map<String, CompanyRole> roles = new HashMap<>();

    /** Employee role assignments: player UUID -> role name. */
    public final Map<String, String> roleAssignments = new HashMap<>();

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

    /** Returns the assigned role name for a player, or null if unassigned. */
    public String roleOf(UUID uuid) {
        return roleAssignments.get(uuid.toString());
    }

    /** Returns the CompanyRole definition for a player, or null. */
    public CompanyRole roleConfigOf(UUID uuid) {
        String roleName = roleOf(uuid);
        if (roleName == null) return null;
        return roles.get(roleName);
    }

    /** True if the player is the owner (CEO) of this business. */
    public boolean isOwner(UUID uuid) {
        return owner.equals(uuid);
    }

    /** True if the player is the owner or has any assigned role. */
    public boolean isOwnerOrEmployee(UUID uuid) {
        return isOwner(uuid) || roleAssignments.containsKey(uuid.toString());
    }

    /** True if the player holds a role with the given flag. */
    public boolean hasFlag(UUID uuid, java.util.function.Predicate<CompanyRole> flagCheck) {
        if (isOwner(uuid)) return true;
        CompanyRole role = roleConfigOf(uuid);
        return role != null && flagCheck.test(role);
    }
}
