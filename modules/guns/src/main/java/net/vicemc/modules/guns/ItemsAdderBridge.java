package net.vicemc.modules.guns;

import org.bukkit.inventory.ItemStack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Optional ItemsAdder integration without a hard compile-time dependency. */
public final class ItemsAdderBridge {

    private ItemsAdderBridge() {
    }

    public static ItemStack customStack(String namespacedId) {
        if (namespacedId == null || namespacedId.isBlank()) {
            return null;
        }
        try {
            Class<?> customStack = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Method getInstance = customStack.getMethod("getInstance", String.class);
            Object stack = getInstance.invoke(null, namespacedId);
            if (stack == null) {
                return null;
            }
            Object item = customStack.getMethod("getItemStack").invoke(stack);
            return item instanceof ItemStack itemStack ? itemStack.clone() : null;
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                 InvocationTargetException ex) {
            return null;
        }
    }

    public static String namespacedId(ItemStack item) {
        if (item == null) {
            return "";
        }
        try {
            Class<?> customStack = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Method byItemStack = customStack.getMethod("byItemStack", ItemStack.class);
            Object stack = byItemStack.invoke(null, item);
            if (stack == null) {
                return "";
            }
            Object id = customStack.getMethod("getNamespacedID").invoke(stack);
            return id instanceof String value ? value : "";
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                 InvocationTargetException ex) {
            return "";
        }
    }
}
