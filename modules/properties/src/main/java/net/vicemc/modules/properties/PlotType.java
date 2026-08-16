package net.vicemc.modules.properties;

import org.bukkit.Material;

/**
 * The type of a plot. Each type has its own ownership limit (a base limit and
 * a higher limit once the player holds a real estate permit from university).
 * Business plot types require the matching government license before they can
 * be bought.
 */
public enum PlotType {

    HOUSE("House", Material.OAK_DOOR),
    APARTMENT("Apartment", Material.BIRCH_DOOR),
    FARM("Farm", Material.WHEAT),
    MINE("Mine", Material.DIAMOND_PICKAXE),
    SHOP("Shop", Material.CHEST),
    FOOD_COMPANY("Food Company", Material.COOKED_BEEF),
    FACTORY("Factory", Material.FURNACE),
    DEALERSHIP("Dealership", Material.SADDLE),
    JEWELRY_STORE("Jewelry Store", Material.DIAMOND),
    BANK("Bank", Material.GOLD_INGOT);

    private final String display;
    private final Material icon;

    PlotType(String display, Material icon) {
        this.display = display;
        this.icon = icon;
    }

    public String display() {
        return display;
    }

    public Material icon() {
        return icon;
    }

    public static PlotType from(String value) {
        if (value == null) {
            return null;
        }
        for (PlotType type : values()) {
            if (type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        return null;
    }
}
