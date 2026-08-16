package net.vicemc.modules.cosmetics;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads cosmetic sets from config and handles the no-drop behavior: outside
 * the Blood Diamond Mine, cosmetic gear is returned on respawn instead of
 * being lost on death.
 */
public final class CosmeticManager {

    private static final NamespacedKey COSMETIC_KEY = NamespacedKey.fromString("vicemc:cosmetic");
    private static final NamespacedKey PIECE_KEY = NamespacedKey.fromString("vicemc:piece");
    private static final NamespacedKey MASK_KEY = NamespacedKey.fromString("vicemc:mask");
    private static final String MINE_TAG = "blooddiamond";

    private final ViceModuleContext ctx;
    private final YamlConfig config;
    private final Map<String, CosmeticSet> sets = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> pendingRestore = new ConcurrentHashMap<>();

    public CosmeticManager(ViceModuleContext ctx, YamlConfig config) {
        this.ctx = ctx;
        this.config = config;
        loadSets();
    }

    private void loadSets() {
        ConfigurationSection section = config.getSection("sets");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            CosmeticSet set = new CosmeticSet();
            set.id = id;
            set.name = config.getString("sets." + id + ".name", id);
            set.mask = config.getBoolean("sets." + id + ".mask", false);
            ConfigurationSection pieces = config.getSection("sets." + id + ".pieces");
            if (pieces == null) {
                continue;
            }
            for (String slot : pieces.getKeys(false)) {
                CosmeticPiece piece = new CosmeticPiece();
                piece.material = Material.matchMaterial(
                        config.getString("sets." + id + ".pieces." + slot + ".material", "LEATHER_HELMET"));
                if (piece.material == null) {
                    continue;
                }
                piece.modelData = config.getInt("sets." + id + ".pieces." + slot + ".model-data", 0);
                String color = config.getString("sets." + id + ".pieces." + slot + ".color", null);
                if (color != null) {
                    String[] rgb = color.split(",");
                    if (rgb.length == 3) {
                        piece.color = Color.fromRGB(Integer.parseInt(rgb[0].trim()),
                                Integer.parseInt(rgb[1].trim()), Integer.parseInt(rgb[2].trim()));
                    }
                }
                set.pieces.put(slot, piece);
            }
            if (!set.pieces.isEmpty()) {
                sets.put(id, set);
            }
        }
    }

    public List<CosmeticSet> sets() {
        return new ArrayList<>(sets.values());
    }

    public CosmeticSet set(String id) {
        return sets.get(id);
    }

    public void giveSet(Player player, CosmeticSet set) {
        for (Map.Entry<String, CosmeticPiece> entry : set.pieces.entrySet()) {
            ItemStack item = ItemBuilder.of(entry.getValue().material)
                    .name(set.name)
                    .modelData(entry.getValue().modelData)
                    .lore("&7Cosmetic - does not drop on death",
                            "&7Slot: &f" + entry.getKey(),
                            set.mask ? "&7Hides your identity during heists" : "&7Collectible")
                    .tag(COSMETIC_KEY, set.id)
                    .tag(PIECE_KEY, entry.getKey())
                    .build();
            if (set.mask) {
                item.editMeta(meta -> meta.getPersistentDataContainer().set(
                        MASK_KEY, org.bukkit.persistence.PersistentDataType.STRING, "true"));
            }
            if (entry.getValue().color != null) {
                item.editMeta(meta -> {
                    if (meta instanceof org.bukkit.inventory.meta.LeatherArmorMeta leather) {
                        leather.setColor(entry.getValue().color);
                    }
                });
            }
            equipOrGive(player, entry.getKey(), item);
        }
    }

    private void equipOrGive(Player player, String slot, ItemStack item) {
        switch (slot) {
            case "helmet" -> player.getInventory().setHelmet(item);
            case "chestplate" -> player.getInventory().setChestplate(item);
            case "leggings" -> player.getInventory().setLeggings(item);
            case "boots" -> player.getInventory().setBoots(item);
            default -> {
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                if (!leftover.isEmpty()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
                }
            }
        }
    }

    public static boolean isCosmetic(ItemStack item) {
        return item != null && ItemBuilder.tag(item, COSMETIC_KEY) != null;
    }

    /**
     * Removes cosmetics from death drops (keeping them for respawn) unless
     * the death happened inside the Blood Diamond Mine.
     */
    public void protectOnDeath(org.bukkit.entity.Player player,
                               List<ItemStack> drops, boolean keepInventory) {
        if (ctx.regions().isInside(player.getLocation(), MINE_TAG)) {
            return;
        }
        if (keepInventory) {
            return;
        }
        List<ItemStack> returned = new ArrayList<>();
        drops.removeIf(item -> {
            if (isCosmetic(item)) {
                returned.add(item);
                return true;
            }
            return false;
        });
        if (!returned.isEmpty()) {
            pendingRestore.put(player.getUniqueId(), returned);
        }
    }

    public void restoreOnRespawn(Player player) {
        List<ItemStack> returned = pendingRestore.remove(player.getUniqueId());
        if (returned == null || returned.isEmpty()) {
            return;
        }
        for (ItemStack item : returned) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            if (!leftover.isEmpty()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
            }
        }
        ctx.notifications().msg(player, "&aYour cosmetic gear was recovered (cosmetics do not drop on death).");
    }
}
