package net.vicemc.api.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent ItemStack builder with support for persistent NBT-style tags so
 * modules can reliably identify their own custom items.
 */
public final class ItemBuilder {

    private final Material material;
    private final ItemStack base;
    private int amount = 1;
    private Component name;
    private final List<Component> lore = new ArrayList<>();
    private Integer modelData;
    private boolean glint;
    private final Map<NamespacedKey, String> tags = new LinkedHashMap<>();

    public ItemBuilder(Material material) {
        this(material, null);
    }

    private ItemBuilder(ItemStack base) {
        this(base == null ? Material.AIR : base.getType(), base);
    }

    private ItemBuilder(Material material, ItemStack base) {
        this.material = material;
        this.base = base;
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(material);
    }

    /** Builds on top of an existing item, preserving every component (model data, item model, tags, ...). */
    public static ItemBuilder of(ItemStack item) {
        return new ItemBuilder(item);
    }

    public ItemBuilder amount(int a) {
        amount = a;
        return this;
    }

    public ItemBuilder name(String n) {
        name = Text.color(n);
        return this;
    }

    public ItemBuilder modelData(int d) {
        modelData = d;
        return this;
    }

    public ItemBuilder lore(String... lines) {
        for (String line : lines) {
            lore.add(Text.color(line));
        }
        return this;
    }

    public ItemBuilder tag(NamespacedKey key, String value) {
        tags.put(key, value);
        return this;
    }

    /** Adds an enchantment glint (no real enchantment) to make the item stand out. */
    public ItemBuilder glow() {
        glint = true;
        return this;
    }

    public ItemStack build() {
        ItemStack item = base != null ? base.clone() : new ItemStack(material, amount);
        item.setAmount(amount);
        item.editMeta(meta -> {
            if (name != null) {
                meta.displayName(name);
            }
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            if (modelData != null) {
                meta.setCustomModelData(modelData);
            }
            if (glint) {
                meta.setEnchantmentGlintOverride(true);
            }
            if (!tags.isEmpty()) {
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                tags.forEach((k, v) -> pdc.set(k, PersistentDataType.STRING, v));
            }
        });
        return item;
    }

    public static String tag(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    public static boolean hasTag(ItemStack item, NamespacedKey key, String value) {
        return value.equals(tag(item, key));
    }
}
