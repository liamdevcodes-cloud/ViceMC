package net.vicemc.modules.stock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A listed company on the exchange. Backed by a player-owned business; the
 * treasury holds the shares that have not been sold yet.
 */
public final class StockCompany {

    /** Company id on the exchange. */
    public int id;
    /** The business this company belongs to. */
    public int businessId;
    public String name;
    /** Total issued shares (10,000 - 1,000,000). */
    public long totalShares;
    /** Holder key -> shares. "COMPANY" is the unsold treasury. */
    public Map<String, Long> holdings = new LinkedHashMap<>();
    /** Current listed price per share. */
    public double price;
    /** Price when the current pricing window started. */
    public double windowStartPrice;
    /** Last window % change. */
    public double changePct;
    /** Money the treasury holds (from share sales). */
    public double treasury;
    public long createdAt;

    public static final String COMPANY = "COMPANY";

    public long heldBy(String key) {
        return holdings.getOrDefault(key, 0L);
    }

    public long heldBy(UUID uuid) {
        return heldBy(uuid.toString());
    }

    /** Shares held by the company itself (unsold). */
    public long treasuryShares() {
        return heldBy(COMPANY);
    }

    /** Shares held by the CEO themselves. */
    public long ceoShares(UUID ceo) {
        return heldBy(ceo);
    }

    /** Shares in public hands (issued minus unsold treasury). */
    public long floatShares() {
        return Math.max(0, totalShares - treasuryShares());
    }

    /** Shares held by everyone except the company treasury and the CEO. */
    public long outstanding(UUID ceo) {
        return Math.max(0, totalShares - treasuryShares() - ceoShares(ceo));
    }

    public void setHeld(String key, long amount) {
        if (amount <= 0) {
            holdings.remove(key);
        } else {
            holdings.put(key, amount);
        }
    }

    public void setHeld(UUID uuid, long amount) {
        setHeld(uuid.toString(), amount);
    }

    public void addHeld(String key, long delta) {
        setHeld(key, heldBy(key) + delta);
    }

    public void addHeld(UUID uuid, long delta) {
        addHeld(uuid.toString(), delta);
    }
}
