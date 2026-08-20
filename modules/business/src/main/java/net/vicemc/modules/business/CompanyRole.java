package net.vicemc.modules.business;

/**
 * A custom role inside a company, defined by the CEO. Each role has an
 * hourly pay rate and a set of permission flags that control what an
 * employee holding this role can do.
 */
public final class CompanyRole {

    public String name;
    public double hourlyRate;
    public boolean canHire;
    public boolean canFire;
    public boolean canSetSalary;
    public boolean canManageSupply;

    public CompanyRole() {
    }

    public CompanyRole(String name, double hourlyRate,
                       boolean canHire, boolean canFire,
                       boolean canSetSalary, boolean canManageSupply) {
        this.name = name;
        this.hourlyRate = hourlyRate;
        this.canHire = canHire;
        this.canFire = canFire;
        this.canSetSalary = canSetSalary;
        this.canManageSupply = canManageSupply;
    }

    /** Returns a readable summary of this role's permission flags. */
    public String flagsSummary() {
        StringBuilder sb = new StringBuilder();
        if (canHire) sb.append("Hire ");
        if (canFire) sb.append("Fire ");
        if (canSetSalary) sb.append("Salary ");
        if (canManageSupply) sb.append("Supply ");
        return sb.isEmpty() ? "None" : sb.toString().trim();
    }
}
