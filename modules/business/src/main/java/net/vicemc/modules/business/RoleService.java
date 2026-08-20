package net.vicemc.modules.business;

import net.vicemc.api.ViceModuleContext;

import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Full CRUD for company roles, role assignments, and role-based permission
 * checks. Every mutation saves through {@link BusinessManager#save()} so the
 * change is immediately persisted.
 */
public final class RoleService {

    private final ViceModuleContext ctx;
    private final BusinessManager manager;

    public RoleService(ViceModuleContext ctx, BusinessManager manager) {
        this.ctx = ctx;
        this.manager = manager;
    }

    // --- Role CRUD ---------------------------------------------------------

    /**
     * Creates a new role definition inside the business.
     *
     * @throws IllegalArgumentException if the role name already exists or
     *                                  hourlyRate is negative
     */
    public void createRole(Business business, String name, double hourlyRate,
                           boolean canHire, boolean canFire,
                           boolean canSetSalary, boolean canManageSupply) {
        if (business.roles.containsKey(name)) {
            throw new IllegalArgumentException("Role '" + name + "' already exists.");
        }
        if (hourlyRate < 0) {
            throw new IllegalArgumentException("Hourly rate cannot be negative.");
        }
        business.roles.put(name, new CompanyRole(name, hourlyRate,
                canHire, canFire, canSetSalary, canManageSupply));
        manager.save();
    }

    /**
     * Removes a role definition and un-assigns any employees that held it.
     */
    public void deleteRole(Business business, String roleName) {
        business.roles.remove(roleName);
        business.roleAssignments.values().removeIf(roleName::equals);
        manager.save();
    }

    /**
     * Updates an existing role's fields.
     *
     * @throws IllegalArgumentException if the role does not exist or
     *                                  hourlyRate is negative
     */
    public void updateRole(Business business, String roleName, double hourlyRate,
                           boolean canHire, boolean canFire,
                           boolean canSetSalary, boolean canManageSupply) {
        CompanyRole role = business.roles.get(roleName);
        if (role == null) {
            throw new IllegalArgumentException("Role '" + roleName + "' does not exist.");
        }
        if (hourlyRate < 0) {
            throw new IllegalArgumentException("Hourly rate cannot be negative.");
        }
        role.hourlyRate = hourlyRate;
        role.canHire = canHire;
        role.canFire = canFire;
        role.canSetSalary = canSetSalary;
        role.canManageSupply = canManageSupply;
        manager.save();
    }

    // --- Assignments -------------------------------------------------------

    /**
     * Assigns a player to a role. Also adds them to the employees set for
     * backwards compatibility with code that still checks {@code employees}.
     *
     * @throws IllegalArgumentException if the role does not exist
     */
    public void assignRole(Business business, UUID player, String roleName) {
        if (!business.roles.containsKey(roleName)) {
            throw new IllegalArgumentException("Role '" + roleName + "' does not exist.");
        }
        business.roleAssignments.put(player.toString(), roleName);
        business.employees.add(player);
        manager.save();
    }

    /**
     * Removes a player's role assignment. Does NOT remove them from the
     * employees set — use {@link BusinessManager} for that.
     */
    public void unassignRole(Business business, UUID player) {
        business.roleAssignments.remove(player.toString());
        manager.save();
    }

    // --- Queries -----------------------------------------------------------

    /**
     * Returns true if the player is the owner (always permitted) or holds a
     * role that satisfies the given check.
     */
    public boolean hasPermission(Business business, UUID player,
                                 Predicate<CompanyRole> check) {
        return business.hasFlag(player, check);
    }

    /**
     * Returns the assigned role name for a player, or {@code null} if unassigned.
     */
    public String getRoleName(Business business, UUID player) {
        return business.roleOf(player);
    }

    /**
     * Returns the {@link CompanyRole} definition for a player, or {@code null}
     * if they have no role.
     */
    public CompanyRole getRole(Business business, UUID player) {
        return business.roleConfigOf(player);
    }

    /**
     * Returns an unmodifiable view of all role definitions.
     */
    public Map<String, CompanyRole> allRoles(Business business) {
        return business.roles;
    }

    /**
     * Returns an unmodifiable view of all role assignments.
     */
    public Map<String, String> allAssignments(Business business) {
        return business.roleAssignments;
    }
}
