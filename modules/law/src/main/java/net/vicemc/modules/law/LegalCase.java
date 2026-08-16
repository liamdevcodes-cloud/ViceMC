package net.vicemc.modules.law;

import java.util.UUID;

/**
 * A legal case. Serious disputes flow into the court system where a judge
 * rules based on the written law book.
 */
public final class LegalCase {

    public int id;
    public UUID defendant;
    public UUID officer;
    public String charge;
    public long filedAt;
    public String status = "COURT";
    public String verdict;
    public double fine;
    public int jailDays;
}
