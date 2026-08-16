package net.vicemc.modules.heists;

/**
 * A configurable heist location with risk-versus-reward values.
 */
public final class HeistSite {

    public String id;
    public String name;
    public String regionTag;
    public double payout;
    public int risk;
    public int durationSeconds;
}
