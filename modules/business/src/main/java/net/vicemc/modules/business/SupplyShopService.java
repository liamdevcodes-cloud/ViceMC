package net.vicemc.modules.business;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the per-business-type supply shop. Licensed business owners and their
 * employees can buy discounted items from a catalog defined in {@code chains.yml}
 * under the {@code supply-catalog} section. Admins can add, edit and remove
 * catalog items at runtime; changes are written back to the config.
 * <p>
 * Per-business stock (remaining purchases before restock) is persisted via
 * {@link net.vicemc.api.service.StorageService} with the key
 * {@code supply:<businessId>}.
 */
public final class SupplyShopService {

    private static final String SUPPLY_PREFIX = "supply:";

    private final ViceModuleContext ctx;
    private final BusinessManager manager;
    private final YamlConfig config;

    public SupplyShopService(ViceModuleContext ctx, BusinessManager manager, YamlConfig config) {
        this.ctx = ctx;
        this.manager = manager;
        this.config = config;
    }

    // --- Catalog queries ---------------------------------------------------

    /**
     * Returns the list of supply item IDs available for a given business type,
     * or an empty list if the type has no catalog section.
     */
    public List<String> catalogIds(String businessType) {
        ConfigurationSection section = config.getSection("supply-catalog." + businessType);
        return section == null ? List.of() : new ArrayList<>(section.getKeys(false));
    }

    /** Whether this business type has any supply items defined. */
    public boolean hasCatalog(String businessType) {
        ConfigurationSection section = config.getSection("supply-catalog." + businessType);
        return section != null && !section.getKeys(false).isEmpty();
    }

    /** Display name of a catalog item. */
    public String itemDisplay(String businessType, String itemId) {
        return config.getString("supply-catalog." + businessType + "." + itemId + ".display", itemId);
    }

    /** Price of a single unit of a catalog item. */
    public double itemPrice(String businessType, String itemId) {
        return config.getDouble("supply-catalog." + businessType + "." + itemId + ".price", 0);
    }

    /** Maximum stock available before restock. */
    public int itemMaxStock(String businessType, String itemId) {
        return config.getInt("supply-catalog." + businessType + "." + itemId + ".max-stock", 64);
    }

    // --- Per-business stock ------------------------------------------------

    /** Current remaining stock for a specific item in a business. */
    public int currentStock(Business business, String itemId) {
        return loadStock(business).getOrDefault(itemId, itemMaxStock(business.type, itemId));
    }

    // --- Admin catalog management ------------------------------------------

    /**
     * Adds or updates a supply catalog item for a business type. Writes the
     * change to {@code chains.yml}.
     */
    public void addItem(String businessType, String itemId, String display,
                        Material material, double price, int maxStock) {
        String base = "supply-catalog." + businessType + "." + itemId;
        config.set(base + ".display", display);
        config.set(base + ".item", material.name());
        config.set(base + ".price", price);
        config.set(base + ".max-stock", maxStock);
        config.save();
    }

    /** Removes an item from the supply catalog. */
    public void removeItem(String businessType, String itemId) {
        config.set("supply-catalog." + businessType + "." + itemId, null);
        config.save();
    }

    // --- Purchase ----------------------------------------------------------

    /**
     * Attempts to purchase supply items for the player's business. Money is
     * withdrawn from the <em>business owner</em>, not the buying employee.
     *
     * @return {@code true} if the purchase succeeded
     */
    public boolean buySupply(Player player, Business business, String itemId, int count) {
        if (count < 1) {
            count = 1;
        }

        if (!business.licensed) {
            player.sendMessage(Text.color("&cYour business is not licensed."));
            return false;
        }
        if (!manager.isOwnerOrEmployee(player.getUniqueId(), business)) {
            player.sendMessage(Text.color("&cYou must own or work at this business."));
            return false;
        }

        ConfigurationSection itemSec = config.getSection("supply-catalog." + business.type + "." + itemId);
        if (itemSec == null) {
            player.sendMessage(Text.color("&cUnknown supply item. See the supply catalog."));
            return false;
        }

        Map<String, Integer> stock = loadStock(business);
        int maxStock = itemSec.getInt("max-stock", 64);
        int remaining = stock.getOrDefault(itemId, maxStock);
        if (remaining < count) {
            player.sendMessage(Text.color("&cNot enough stock. Remaining: &f" + remaining + "&c."));
            return false;
        }

        double unitPrice = itemSec.getDouble("price", 0);
        double total = unitPrice * count;
        var result = ctx.economy().withdraw(business.owner, total, "supply purchase: " + itemId);
        if (!result.success()) {
            player.sendMessage(Text.color("&cThe business owner cannot afford &f"
                    + Text.moneyPlain(total) + "&c."));
            return false;
        }

        // Deduct stock
        stock.put(itemId, remaining - count);
        saveStock(business, stock);

        // Give items to the buyer
        String materialName = itemSec.getString("item", "STONE");
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.STONE;
        }
        int amount = itemSec.getInt("amount", 1) * count;
        ItemStack stack = new ItemStack(material, amount);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(),
                    leftover.values().iterator().next());
        }

        String display = itemSec.getString("display", itemId);
        player.sendMessage(Text.color("&aBought &f" + amount + "x " + display
                + " &afor &f" + Text.moneyPlain(total) + "&a."));
        return true;
    }

    // --- Restock -----------------------------------------------------------

    /**
     * Adds {@code restockAmount} to every item's current stock across all
     * businesses, capped at each item's max-stock. Intended to be called by a
     * scheduled task (e.g. hourly).
     */
    public void restockAll(int restockAmount) {
        for (Business business : manager.all()) {
            Map<String, Integer> stock = loadStock(business);
            boolean changed = false;
            for (String itemId : catalogIds(business.type)) {
                int max = itemMaxStock(business.type, itemId);
                int current = stock.getOrDefault(itemId, max);
                if (current < max) {
                    stock.put(itemId, Math.min(max, current + restockAmount));
                    changed = true;
                }
            }
            if (changed) {
                saveStock(business, stock);
            }
        }
    }

    // --- Stock persistence -------------------------------------------------

    private Map<String, Integer> loadStock(Business business) {
        return ctx.storage().getModuleData("business", SUPPLY_PREFIX + business.id)
                .<Map<String, Integer>>map(json -> {
                    Map<String, Integer> map = net.vicemc.api.util.Json.fromJson(
                            json, new com.google.gson.reflect.TypeToken<Map<String, Integer>>() {
                            }.getType());
                    return map != null ? new HashMap<>(map) : new HashMap<>();
                })
                .orElseGet(() -> new HashMap<>());
    }

    private void saveStock(Business business, Map<String, Integer> stock) {
        ctx.storage().setModuleData("business", SUPPLY_PREFIX + business.id,
                net.vicemc.api.util.Json.toJson(stock));
    }
}
