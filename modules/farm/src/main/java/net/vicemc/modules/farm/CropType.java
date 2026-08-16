package net.vicemc.modules.farm;

import org.bukkit.Material;

/**
 * A configurable crop: the planted block, the harvested item, its sale value
 * to the government and the farming level required to harvest it. Crops are
 * defined in farming.yml and never hard-coded, so admins can tune the economy.
 */
public final class CropType {

    public final String id;
    public final Material block;
    public final Material item;
    public final double value;
    public final double xp;
    public final int level;
    public final int regenSeconds;

    public CropType(String id, Material block, Material item, double value,
                    double xp, int level, int regenSeconds) {
        this.id = id;
        this.block = block;
        this.item = item;
        this.value = value;
        this.xp = xp;
        this.level = level;
        this.regenSeconds = regenSeconds;
    }

    /** Friendly display name, e.g. "WHEAT" -> "Wheat". */
    public String display() {
        String[] words = item.name().toLowerCase().replace('_', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            }
        }
        return sb.toString().trim();
    }
}
