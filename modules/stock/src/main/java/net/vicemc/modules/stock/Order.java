package net.vicemc.modules.stock;

import java.util.UUID;

/**
 * A resting limit order on the public order book. Buy orders reserve the
 * funds, sell orders reserve the shares, until matched or cancelled.
 */
public final class Order {

    public long id;
    public int companyId;
    /** true = buy, false = sell. */
    public boolean buy;
    /** Order owner (always a player, even for treasury sells). */
    public UUID player;
    /** Who provides the shares on a sell: a player uuid or "COMPANY". */
    public String seller;
    /** Remaining shares on this order. */
    public long shares;
    /** Limit price per share. */
    public double price;
    /** Reserved money backing a buy order (price * shares). */
    public double escrow;
    public long createdAt;

    public boolean isCompanySale() {
        return StockCompany.COMPANY.equals(seller);
    }
}
