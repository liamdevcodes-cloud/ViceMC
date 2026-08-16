package net.vicemc.modules.business;

import java.util.Locale;

/**
 * The purchasable business categories from the design brief.
 */
public enum BusinessType {
    FARM,
    FACTORY,
    DEALERSHIP,
    SHOP,
    FOOD_COMPANY,
    JEWELRY_STORE,
    BANK,
    MINE;

    public String display() {
        String s = name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
