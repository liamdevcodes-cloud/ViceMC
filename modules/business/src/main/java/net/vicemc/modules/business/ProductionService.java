package net.vicemc.modules.business;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Implements the legal production chains: factory crafting, farm produce and
 * government stock, plus the fixed government buy prices.
 */
public final class ProductionService {

    private final ViceModuleContext ctx;
    private final YamlConfig config;
    private final BusinessManager manager;
    private final NamespacedKey productKey;

    public ProductionService(ViceModuleContext ctx, YamlConfig config, BusinessManager manager) {
        this.ctx = ctx;
        this.config = config;
        this.manager = manager;
        this.productKey = new NamespacedKey(ctx.plugin(), "product");
    }

    public List<String> recipeIds() {
        ConfigurationSection section = config.getSection("factory-recipes");
        return section == null ? List.of() : section.getKeys(false).stream().toList();
    }

    public String recipeDisplay(String id) {
        return config.getString("factory-recipes." + id + ".display", id);
    }

    // --- Factory ----------------------------------------------------------

    public boolean craft(Player player, String recipeId, int count) {
        if (count < 1) {
            count = 1;
        }
        ConfigurationSection recipe = config.getSection("factory-recipes." + recipeId);
        if (recipe == null) {
            player.sendMessage(Text.color("&cUnknown recipe. See /factory list."));
            return false;
        }
        Business factory = manager.ownedOrEmployedOfType(player.getUniqueId(), "FACTORY");
        if (factory == null) {
            player.sendMessage(Text.color("&cYou must own or work at a factory."));
            return false;
        }

        ConfigurationSection inputs = recipe.getConfigurationSection("inputs");
        if (inputs == null) {
            return false;
        }
        Map<Material, Integer> needed = new HashMap<>();
        for (String mat : inputs.getKeys(false)) {
            Material material = Material.matchMaterial(mat);
            if (material == null) {
                continue;
            }
            int amount = inputs.getInt(mat) * count;
            if (!player.getInventory().containsAtLeast(new ItemStack(material), amount)) {
                player.sendMessage(Text.color("&cMissing " + amount + " " + material.name().toLowerCase(Locale.ROOT) + "."));
                return false;
            }
            needed.put(material, amount);
        }

        String outputMaterial = recipe.getString("output", "STONE");
        int outputAmount = recipe.getInt("output-amount", 1) * count;
        Material out = Material.matchMaterial(outputMaterial);
        if (out == null) {
            out = Material.STONE;
        }

        for (Map.Entry<Material, Integer> entry : needed.entrySet()) {
            player.getInventory().removeItem(new ItemStack(entry.getKey(), entry.getValue()));
        }
        ItemStack product = ItemBuilder.of(out)
                .amount(outputAmount)
                .name(recipe.getString("display", recipeId))
                .tag(productKey, recipeId)
                .lore("&7Factory product - sell to the government")
                .build();
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(product);
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
        }
        player.sendMessage(Text.color("&aCrafted &f" + outputAmount + "x " + config.getString("factory-recipes." + recipeId + ".display", recipeId)));
        return true;
    }

    /**
     * Sells tagged factory products to the government at the fixed price.
     */
    public boolean sellProduct(Player player, String recipeId, int count) {
        ConfigurationSection recipe = config.getSection("factory-recipes." + recipeId);
        if (recipe == null) {
            player.sendMessage(Text.color("&cUnknown product."));
            return false;
        }
        Business factory = manager.ownedOfType(player.getUniqueId(), "FACTORY");
        Business jewelry = manager.ownedOfType(player.getUniqueId(), "JEWELRY_STORE");
        if (factory == null && jewelry == null) {
            player.sendMessage(Text.color("&cYou must own a factory or jewelry store to sell products."));
            return false;
        }
        int sold = 0;
        double price = recipe.getDouble("government-price", 0);
        for (ItemStack item : player.getInventory().getContents()) {
            if (sold >= count) {
                break;
            }
            if (item != null && ItemBuilder.hasTag(item, productKey, recipeId)) {
                player.getInventory().removeItem(item.asQuantity(1));
                sold++;
            }
        }
        if (sold == 0) {
            player.sendMessage(Text.color("&cYou have no " + config.getString("factory-recipes." + recipeId + ".display", recipeId) + " to sell."));
            return false;
        }
        double total = sold * price;
        ctx.economy().deposit(player.getUniqueId(), total, "government purchase: " + recipeId);
        player.sendMessage(Text.color("&aSold &f" + sold + "x " + config.getString("factory-recipes." + recipeId + ".display", recipeId)
                + "&a to the government for &f" + Text.moneyPlain(total)));
        return true;
    }

    // --- Farm -------------------------------------------------------------

    public boolean farmHarvest(Player player) {
        Business farm = manager.ownedOfType(player.getUniqueId(), "FARM");
        if (farm == null) {
            player.sendMessage(Text.color("&cYou must own a farm."));
            return false;
        }
        long today = java.time.LocalDate.now().toEpochDay();
        String last = ctx.storage().getModuleData("business", "farm:" + player.getUniqueId()).orElse("0");
        if (last.equals(String.valueOf(today))) {
            player.sendMessage(Text.color("&cYou already harvested today. Come back tomorrow."));
            return false;
        }
        ConfigurationSection harvest = config.getSection("farm.harvest");
        if (harvest != null) {
            for (String mat : harvest.getKeys(false)) {
                Material material = Material.matchMaterial(mat);
                if (material == null) {
                    continue;
                }
                int amount = harvest.getInt(mat);
                ItemStack stack = new ItemStack(material, amount);
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
                if (!leftover.isEmpty()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
                }
            }
        }
        ctx.storage().setModuleData("business", "farm:" + player.getUniqueId(), String.valueOf(today));
        player.sendMessage(Text.color("&aHarvested your farm. Sell produce with &e/farm sell&a."));
        return true;
    }

    public boolean farmSell(Player player) {
        Business farm = manager.ownedOfType(player.getUniqueId(), "FARM");
        if (farm == null) {
            player.sendMessage(Text.color("&cYou must own a farm."));
            return false;
        }
        ConfigurationSection prices = config.getSection("farm.government-price");
        if (prices == null) {
            return false;
        }
        double total = 0;
        int items = 0;
        for (String mat : prices.getKeys(false)) {
            Material material = Material.matchMaterial(mat);
            if (material == null) {
                continue;
            }
            double price = prices.getDouble(mat);
            int count = 0;
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && item.getType() == material) {
                    count += item.getAmount();
                }
            }
            if (count > 0) {
                player.getInventory().removeItem(new ItemStack(material, count));
                total += count * price;
                items += count;
            }
        }
        if (items == 0) {
            player.sendMessage(Text.color("&cYou have no farm produce to sell."));
            return false;
        }
        ctx.economy().deposit(player.getUniqueId(), total, "government purchase: farm produce");
        player.sendMessage(Text.color("&aSold &f" + items + "&a produce items to the government for &f" + Text.moneyPlain(total)));
        return true;
    }

    // --- Government stock -------------------------------------------------

    public List<String> stockIds() {
        ConfigurationSection section = config.getSection("government-stock");
        return section == null ? List.of() : section.getKeys(false).stream().toList();
    }

    public boolean canBuyStock(Player player, String stockId) {
        return canBuyStockFor(player.getUniqueId(), stockId);
    }

    /**
     * Checks whether the given citizen {@code owner} may buy the stock: they
     * must own a licensed business of every required type. Used by the
     * government counter when serving a citizen.
     */
    public boolean canBuyStockFor(UUID owner, String stockId) {
        ConfigurationSection stock = config.getSection("government-stock." + stockId);
        if (stock == null) {
            return false;
        }
        for (String required : stock.getStringList("requires")) {
            Business business = manager.ownedOfType(owner, required);
            if (business == null || !business.licensed || !manager.hasLicense(owner, required)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Licensed business buys discounted government stock (wholesale).
     */
    public boolean buyStock(Player player, String stockId, int count) {
        return buyStockFor(player, player.getUniqueId(), stockId, count, true);
    }

    /**
     * Government-serving variant: the employee buys wholesale stock for the
     * citizen {@code owner}. Money is withdrawn from the citizen's account.
     */
    public boolean buyStockFor(Player actor, UUID owner, String stockId, int count) {
        return buyStockFor(actor, owner, stockId, count, false);
    }

    private boolean buyStockFor(Player actor, UUID owner, String stockId, int count, boolean self) {
        if (count < 1) {
            count = 1;
        }
        ConfigurationSection stock = config.getSection("government-stock." + stockId);
        if (stock == null) {
            actor.sendMessage(Text.color("&cUnknown stock. See /shop stock."));
            return false;
        }
        Business business = null;
        for (String required : stock.getStringList("requires")) {
            business = manager.ownedOfType(owner, required);
            if (business != null && business.licensed && manager.hasLicense(owner, required)) {
                break;
            }
        }
        if (business == null) {
            actor.sendMessage(Text.color("&c" + (self ? "You need" : "They need")
                    + " a licensed business of the required type to buy this stock."));
            return false;
        }
        double wholesale = stock.getDouble("wholesale", 0) * count;
        var result = ctx.economy().withdraw(owner, wholesale, "government stock purchase: " + stockId);
        if (!result.success()) {
            actor.sendMessage(Text.color("&c" + (self ? "You cannot" : "They cannot")
                    + " afford the wholesale price: " + Text.moneyPlain(wholesale)));
            return false;
        }
        business.stock.merge(stockId, count, Integer::sum);
        manager.save();
        String display = stock.getString("display", stockId);
        actor.sendMessage(Text.color("&aBought &f" + count + "x &aof '" + display + "' for &f"
                + Text.moneyPlain(wholesale) + (self ? "." : " for " + offlineName(owner) + ".")));
        if (!self) {
            Player citizen = Bukkit.getPlayer(owner);
            if (citizen != null) {
                citizen.sendMessage(Text.color("&aThe government bought you &f" + count + "x &aof '"
                        + display + "' for &f" + Text.moneyPlain(wholesale) + "&a."));
            }
        }
        return true;
    }

    private String offlineName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    /**
     * Dealership sells one vehicle from its stock to a customer at retail.
     */
    public boolean dealerSell(Player dealerPlayer, Player customer, String stockId) {
        ConfigurationSection stock = config.getSection("government-stock." + stockId);
        if (stock == null || !stock.getStringList("requires").contains("DEALERSHIP")) {
            dealerPlayer.sendMessage(Text.color("&cUnknown vehicle."));
            return false;
        }
        Business dealership = manager.ownedOfType(dealerPlayer.getUniqueId(), "DEALERSHIP");
        if (dealership == null || !dealership.licensed) {
            dealerPlayer.sendMessage(Text.color("&cYou must own a licensed dealership."));
            return false;
        }
        if (dealership.stockOf(stockId) < 1) {
            dealerPlayer.sendMessage(Text.color("&cNo stock. Restock with &e/shop buy " + stockId + "&c."));
            return false;
        }
        double retail = stock.getDouble("retail", stock.getDouble("wholesale", 0));
        var payment = ctx.economy().transfer(customer.getUniqueId(), dealerPlayer.getUniqueId(), retail, "vehicle purchase: " + stockId);
        if (!payment.success()) {
            dealerPlayer.sendMessage(Text.color("&cCustomer cannot afford that vehicle."));
            return false;
        }
        dealership.stock.merge(stockId, -1, Integer::sum);
        manager.save();
        Material item = Material.matchMaterial(stock.getString("item", "SADDLE"));
        ItemStack vehicle = ItemBuilder.of(item == null ? Material.SADDLE : item)
                .amount(stock.getInt("amount", 1))
                .name(stock.getString("display", stockId))
                .build();
        Map<Integer, ItemStack> leftover = customer.getInventory().addItem(vehicle);
        if (!leftover.isEmpty()) {
            customer.getWorld().dropItemNaturally(customer.getLocation(), leftover.values().iterator().next());
        }
        dealerPlayer.sendMessage(Text.color("&aSold '" + stock.getString("display", stockId)
                + "' to " + customer.getName() + " for &f" + Text.moneyPlain(retail)));
        return true;
    }

    public String stockInfo(String stockId) {
        ConfigurationSection stock = config.getSection("government-stock." + stockId);
        if (stock == null) {
            return null;
        }
        return "&f" + stock.getString("display", stockId)
                + "&7 - wholesale &f" + Text.moneyPlain(stock.getDouble("wholesale", 0))
                + "&7 retail &f" + Text.moneyPlain(stock.getDouble("retail", 0))
                + "&7 (" + String.join(", ", stock.getStringList("requires")) + ")";
    }
}
