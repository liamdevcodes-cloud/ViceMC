package net.vicemc.modules.business;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Automatic hourly salary payouts based on accumulated work playtime and role
 * hourly rates. The {@code BusinessModule.onEnable()} schedules
 * {@link #paySalaries()} once per in-game hour (72 000 ticks).
 * <p>
 * For each business the service iterates over every role assignment, looks up
 * the employee's accumulated work time and role rate, calculates pay as
 * {@code (seconds / 3600) * hourlyRate}, withdraws from the business owner
 * and deposits to the employee. Work time is reset after a successful payout.
 * <p>
 * No additional storage is needed — work times live in
 * {@link WorkPlaytimeService} and business data in {@link BusinessManager}.
 */
public final class SalaryService {

    private final ViceModuleContext ctx;
    private final BusinessManager manager;
    private final RoleService roleService;
    private final WorkPlaytimeService workTime;

    public SalaryService(ViceModuleContext ctx, BusinessManager manager,
                         RoleService roleService, WorkPlaytimeService workTime) {
        this.ctx = ctx;
        this.manager = manager;
        this.roleService = roleService;
        this.workTime = workTime;
    }

    // --- Hourly payout -----------------------------------------------------

    /**
     * The main payout method, called every hour by a scheduled task. For each
     * business:
     * <ol>
     *   <li>Iterate over {@code business.roleAssignments} (UUID → roleName).</li>
     *   <li>Look up the employee's {@link CompanyRole} and work time.</li>
     *   <li>Calculate fractional pay.</li>
     *   <li>Withdraw from the business owner; deposit to the employee.</li>
     *   <li>Reset the employee's work time.</li>
     * </ol>
     * If the business owner cannot afford the payout, the employee is skipped
     * and the owner receives a warning.
     */
    public void paySalaries() {
        for (Business business : manager.all()) {
            if (business.roleAssignments.isEmpty()) {
                continue;
            }

            for (Map.Entry<String, String> entry : business.roleAssignments.entrySet()) {
                UUID employeeUUID;
                try {
                    employeeUUID = UUID.fromString(entry.getKey());
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                String roleName = entry.getValue();
                CompanyRole role = business.roles.get(roleName);
                if (role == null) {
                    continue;
                }

                long workSeconds = workTime.getWorkSeconds(employeeUUID, business.id);
                double pay = (workSeconds / 3600.0) * role.hourlyRate;
                if (pay <= 0) {
                    continue;
                }

                String employeeName = offlineName(employeeUUID);

                // Withdraw from business owner
                var withdrawal = ctx.economy().withdraw(business.owner, pay,
                        "salary: " + roleName + " to " + employeeName);
                if (!withdrawal.success()) {
                    Player owner = Bukkit.getPlayer(business.owner);
                    if (owner != null) {
                        ctx.notifications().warn(owner,
                                "&cCannot afford salary for &f" + employeeName
                                        + " &c(" + roleName + "): " + Text.moneyPlain(pay) + "&c.");
                    }
                    continue;
                }

                // Deposit to employee
                ctx.economy().deposit(employeeUUID, pay,
                        "salary from " + business.name);

                // Reset work time after payout
                workTime.resetWorkTime(business.id, employeeUUID);

                // Notify
                Player owner = Bukkit.getPlayer(business.owner);
                if (owner != null) {
                    ctx.notifications().msg(owner,
                            "&aPaid &f" + employeeName + " &a" + Text.moneyPlain(pay)
                                    + " &afor &f" + formatHours(workSeconds) + " &ahours as &f" + roleName + "&a.");
                }
                Player employee = Bukkit.getPlayer(employeeUUID);
                if (employee != null) {
                    ctx.notifications().msg(employee,
                            "&aYou received &f" + Text.moneyPlain(pay)
                                    + " &asalary from &f" + business.name
                                    + " &afor &f" + formatHours(workSeconds) + " &ahours as &f" + roleName + "&a.");
                }
            }
        }
    }

    // --- Preview -----------------------------------------------------------

    /**
     * Calculates what each employee would earn if paid now (without actually
     * paying). Returns a formatted list of lines.
     */
    public List<String> previewPayouts(int businessId) {
        List<String> lines = new ArrayList<>();
        Business business = manager.byId(businessId).orElse(null);
        if (business == null) {
            return lines;
        }

        for (Map.Entry<String, String> entry : business.roleAssignments.entrySet()) {
            UUID employeeUUID;
            try {
                employeeUUID = UUID.fromString(entry.getKey());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            String roleName = entry.getValue();
            CompanyRole role = business.roles.get(roleName);
            if (role == null) {
                continue;
            }

            long workSeconds = workTime.getWorkSeconds(employeeUUID, business.id);
            double hours = workSeconds / 3600.0;
            double pay = hours * role.hourlyRate;

            String employeeName = offlineName(employeeUUID);
            lines.add(employeeName + " - " + roleName
                    + " - Hours: " + String.format("%.2f", hours)
                    + " - Pay: " + Text.moneyPlain(pay));
        }
        return lines;
    }

    /**
     * Calculates pay for a single employee based on current work time and
     * role rate.
     */
    public double calculatePay(Business business, UUID employee) {
        String roleName = business.roleOf(employee);
        if (roleName == null) {
            return 0;
        }
        CompanyRole role = business.roles.get(roleName);
        if (role == null) {
            return 0;
        }
        long workSeconds = workTime.getWorkSeconds(employee, business.id);
        return (workSeconds / 3600.0) * role.hourlyRate;
    }

    // --- Helpers -----------------------------------------------------------

    private String formatHours(long seconds) {
        return String.format("%.2f", seconds / 3600.0);
    }

    private String offlineName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }
}
