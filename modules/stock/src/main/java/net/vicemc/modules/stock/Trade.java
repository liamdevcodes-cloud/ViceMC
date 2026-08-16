package net.vicemc.modules.stock;

import java.util.UUID;

/**
 * A public, permanently-visible record of a matched trade. The seller is a
 * player uuid, or "COMPANY" when shares were sold from the company treasury.
 */
public final class Trade {

    public long id;
    public int companyId;
    public UUID buyer;
    public String seller;
    public long shares;
    /** Price per share the trade executed at. */
    public double price;
    public long createdAt;

    public boolean isCompanySale() {
        return StockCompany.COMPANY.equals(seller);
    }
}
