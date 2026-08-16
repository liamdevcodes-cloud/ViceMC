package net.vicemc.modules.government;

/**
 * A citizen-submitted subsidy application stored for government review.
 */
public final class SubsidyRequest {

    public int id;
    public String playerName;
    public String playerUuid;
    public int businessId;
    public String businessName;
    public double amount;
    public String reason;
    public long createdAt;
    public String status = "PENDING";
}
