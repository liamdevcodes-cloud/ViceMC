package net.vicemc.modules.lifestyle;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of custom food/drink definitions and the refundable empty bottle,
 * all read from lifestyle.yml so admins can add items without code changes.
 */
public final class LifestyleItems {

    private final ViceModuleContext ctx;
    private final YamlConfig config;
    private final NamespacedKey itemKey;
    private final NamespacedKey bottleKey;
    private final Map<String, LifestyleItem> items = new LinkedHashMap<>();
    private final Map<Material, Double> vanillaFitness = new HashMap<>();
    private String emptyMaterial;
    private int emptyModelData;
    private String emptyName;
    private List<String> emptyLore;

    public LifestyleItems(ViceModuleContext ctx, YamlConfig config) {
        this.ctx = ctx;
        this.config = config;
        this.itemKey = new NamespacedKey(ctx.plugin(), "lifestyle_item");
        this.bottleKey = new NamespacedKey(ctx.plugin(), "lifestyle_bottle");
        reload();
    }

    public void reload() {
        items.clear();
        vanillaFitness.clear();
        ConfigurationSection section = config.getSection("items");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                LifestyleItem item = new LifestyleItem();
                item.id = id;
                item.kind = section.getString(id + ".kind", "FOOD");
                item.material = section.getString(id + ".material", "APPLE");
                item.modelData = section.getInt(id + ".model-data", 0);
                item.name = section.getString(id + ".name", "&eNew Item");
                item.lore = section.getStringList(id + ".lore");
                item.hunger = section.getInt(id + ".hunger", 0);
                item.saturation = section.getDouble(id + ".saturation", 0);
                item.thirstRestore = section.getDouble(id + ".thirst-restore", 0);
                item.restRestore = section.getDouble(id + ".rest-restore", 0);
                item.fitness = section.getDouble(id + ".fitness", 0);
                item.price = section.getDouble(id + ".price", 0);
                item.leavesEmptyBottle = section.getBoolean(id + ".leaves-empty-bottle", false);
                items.put(id, item);
            }
        }
        ConfigurationSection fitness = config.getSection("vanilla-fitness");
        if (fitness != null) {
            for (String mat : fitness.getKeys(false)) {
                Material material = Material.matchMaterial(mat);
                if (material != null) {
                    vanillaFitness.put(material, fitness.getDouble(mat, 0));
                }
            }
        }
        emptyMaterial = config.getString("bottle.empty-material", "GLASS_BOTTLE");
        emptyModelData = config.getInt("bottle.empty-model-data", 900);
        emptyName = config.getString("bottle.empty-name", "&bEmpty Can");
        emptyLore = config.getStringList("bottle.empty-lore");
    }

    public NamespacedKey itemKey() {
        return itemKey;
    }

    public NamespacedKey bottleKey() {
        return bottleKey;
    }

    public Optional<LifestyleItem> byId(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(items.get(id));
    }

    public List<LifestyleItem> all() {
        List<LifestyleItem> list = new ArrayList<>(items.values());
        list.sort(Comparator.comparing(i -> i.id));
        return list;
    }

    /** Builds a custom food/drink item stack tagged with its definition id. */
    public ItemStack build(LifestyleItem item, int amount) {
        Material material = Material.matchMaterial(item.material);
        ItemBuilder builder = ItemBuilder.of(material == null ? Material.APPLE : material)
                .amount(amount)
                .name(item.name)
                .tag(itemKey, item.id);
        if (item.modelData != 0) {
            builder.modelData(item.modelData);
        }
        if (!item.lore.isEmpty()) {
            builder.lore(item.lore.toArray(new String[0]));
        }
        return builder.build();
    }

    /** The refundable empty bottle (statiegeld) item left behind by drinks. */
    public ItemStack emptyBottle() {
        Material material = Material.matchMaterial(emptyMaterial);
        ItemBuilder builder = ItemBuilder.of(material == null ? Material.GLASS_BOTTLE : material)
                .name(emptyName)
                .tag(bottleKey, "true");
        if (emptyModelData != 0) {
            builder.modelData(emptyModelData);
        }
        if (!emptyLore.isEmpty()) {
            builder.lore(emptyLore.toArray(new String[0]));
        }
        return builder.build();
    }

    public boolean isEmptyBottle(ItemStack item) {
        return "true".equals(ItemBuilder.tag(item, bottleKey));
    }

    /** Fitness delta for an untagged vanilla food, or null if not listed. */
    public Double vanillaFitness(Material material) {
        return vanillaFitness.get(material);
    }

    public int nextId(String kind) {
        String prefix = kind.equalsIgnoreCase("DRINK") ? "drink" : "food";
        int n = 1;
        while (items.containsKey(prefix + n)) {
            n++;
        }
        return n;
    }

    public void saveItem(LifestyleItem item) {
        config.set("items." + item.id + ".kind", item.kind);
        config.set("items." + item.id + ".material", item.material);
        config.set("items." + item.id + ".model-data", item.modelData);
        config.set("items." + item.id + ".name", item.name);
        config.set("items." + item.id + ".lore", item.lore);
        config.set("items." + item.id + ".hunger", item.hunger);
        config.set("items." + item.id + ".saturation", item.saturation);
        config.set("items." + item.id + ".thirst-restore", item.thirstRestore);
        config.set("items." + item.id + ".rest-restore", item.restRestore);
        config.set("items." + item.id + ".fitness", item.fitness);
        config.set("items." + item.id + ".price", item.price);
        config.set("items." + item.id + ".leaves-empty-bottle", item.leavesEmptyBottle);
        config.save();
        reload();
    }

    public void deleteItem(String id) {
        config.set("items." + id, null);
        config.save();
        reload();
    }

    public void renameItem(String id, String name) {
        config.set("items." + id + ".name", name);
        config.save();
        reload();
    }
}
