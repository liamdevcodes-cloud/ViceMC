package net.vicemc.modules.cosmetics;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * A configurable armor piece inside a cosmetic set.
 */
public final class CosmeticPiece {

    public Material material;
    public int modelData;
    public Color color;

    public ItemStack base() {
        ItemStack item = new ItemStack(material, 1);
        item.editMeta(meta -> {
            meta.setCustomModelData(modelData);
            if (color != null && meta instanceof org.bukkit.inventory.meta.LeatherArmorMeta leather) {
                leather.setColor(color);
            }
        });
        return item;
    }
}
