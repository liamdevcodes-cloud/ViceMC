package net.vicemc.modules.guns;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;

final class ItemModelSupport {
    private ItemModelSupport() {
    }

    static String read(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        try {
            ItemMeta meta = item.getItemMeta();
            Method has = ItemMeta.class.getMethod("hasItemModel");
            if (!(has.invoke(meta) instanceof Boolean present) || !present) return null;
            Object model = ItemMeta.class.getMethod("getItemModel").invoke(meta);
            return model instanceof NamespacedKey key ? key.toString() : null;
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    static void apply(ItemStack item, String modelId) {
        if (item == null || modelId == null || modelId.isBlank()) return;
        try {
            NamespacedKey key = NamespacedKey.fromString(modelId);
            if (key == null) return;
            item.editMeta(meta -> {
                try {
                    ItemMeta.class.getMethod("setItemModel", NamespacedKey.class).invoke(meta, key);
                } catch (ReflectiveOperationException ignored) {
                    // Older Paper APIs simply do not expose the item model component.
                }
            });
        } catch (IllegalArgumentException ignored) {
            // Invalid stored model IDs are ignored and the legacy model data remains usable.
        }
    }
}
