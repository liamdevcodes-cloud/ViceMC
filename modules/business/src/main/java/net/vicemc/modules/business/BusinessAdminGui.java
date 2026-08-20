package net.vicemc.modules.business;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin GUI for managing the business supply catalog. Opened via
 * {@code /business admin} (requires {@code vicemc.business.admin}).
 */
public final class BusinessAdminGui {

    private final ViceModuleContext ctx;
    private final BusinessModule module;
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();

    public BusinessAdminGui(ViceModuleContext ctx, BusinessModule module) {
        this.ctx = ctx;
        this.module = module;
    }

    // --- Admin Dashboard ---------------------------------------------------

    public void openAdminDashboard(Player player) {
        var builder = ctx.gui().builder(GuiKit.title("Business Admin"), 6);
        GuiKit.frame(builder);
        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&6How it works",
                "&7Admin panel for managing",
                "&7business supply catalogs.",
                "&7Add, edit and remove items."), GuiKit.NONE);
        long totalBusinesses = module.manager().all().size();
        long totalRoles = module.manager().all().stream()
                .mapToLong(b -> b.roles.size()).sum();
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.CHEST)
                .name("&6Business Admin")
                .lore("&7Total businesses: &f" + totalBusinesses,
                        "&7Total roles: &f" + totalRoles)
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.EMERALD, "&6Supply Catalog",
                "&7Manage supply shop catalogs",
                "&7for all business types."), (p, c) -> openSupplyTypePicker(p));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.CHEST, "&6Restock All",
                "&7Restock all supply catalogs",
                "&7for every business type."), (p, c) -> openRestockAll(p));

        builder.open(player);
    }

    // --- Supply Type Picker ------------------------------------------------

    private void openSupplyTypePicker(Player player) {
        var builder = ctx.gui().builder(GuiKit.title("Supply Catalog"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the admin dashboard"), (p, c) -> openAdminDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.EMERALD, "&6Select Business Type",
                "&7Choose a type to manage",
                "&7its supply catalog."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        BusinessType[] types = BusinessType.values();
        for (int i = 0; i < types.length; i++) {
            BusinessType type = types[i];
            boolean hasCatalog = module.supplyShop().hasCatalog(type.name());
            Material icon = hasCatalog ? Material.EMERALD_BLOCK : Material.GRAY_DYE;
            String status = hasCatalog ? "&aActive catalog" : "&7No catalog yet";
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.icon(icon,
                    "&e" + type.display(), status, "&aClick to manage."), (p, c) -> {
                if (!hasCatalog) {
                    ctx.notifications().msg(p, "&eCreating empty catalog for " + type.display() + ".");
                }
                openSupplyCatalogEditor(p, type.name());
            });
        }
        builder.open(player);
    }

    // --- Supply Catalog Editor ---------------------------------------------

    private void openSupplyCatalogEditor(Player player, String businessType) {
        List<String> items = module.supplyShop().catalogIds(businessType);
        var builder = ctx.gui().builder(GuiKit.title(businessType + " Catalog"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the type picker"), (p, c) -> openSupplyTypePicker(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.EMERALD, "&6" + businessType + " Catalog",
                "&7" + items.size() + " item(s).",
                "&7Click an item to edit.",
                "&7Add new items below."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        for (int i = 0; i < Math.min(items.size(), GuiKit.GRID_SIZE); i++) {
            String itemId = items.get(i);
            String display = module.supplyShop().itemDisplay(businessType, itemId);
            double price = module.supplyShop().itemPrice(businessType, itemId);
            int maxStock = module.supplyShop().itemMaxStock(businessType, itemId);
            Material mat = Material.matchMaterial(itemId);
            if (mat == null) {
                mat = Material.PAPER;
            }
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(mat)
                    .name("&e" + display)
                    .lore("&7ID: &f" + itemId,
                            "&7Price: &f" + GuiKit.fmt(price),
                            "&7Max stock: &f" + maxStock,
                            "&aClick to edit.")
                    .build(), (p, c) -> openSupplyItemEdit(p, businessType, itemId));
        }

        builder.item(GuiKit.ACTION_5, GuiKit.cta(Material.LIME_WOOL, "&aAdd Item",
                "&7Add a new supply item",
                "&7to this catalog."), (p, c) -> {
            module.prompts().prompt(p, "&eType the item ID (e.g. iron_ingot):", (pl, input) -> {
                String id = input.trim().toUpperCase().replace(" ", "_");
                if (id.isEmpty()) {
                    ctx.notifications().warn(pl, "Invalid item ID.");
                    openSupplyCatalogEditor(pl, businessType);
                    return;
                }
                module.supplyShop().addItem(businessType, id,
                        id.replace("_", " ").toLowerCase(), Material.PAPER, 0.0, 100);
                ctx.notifications().msg(pl, "&aCreated &f" + id + "&a. Configure it below.");
                openSupplyItemEdit(pl, businessType, id);
            });
        });

        builder.open(player);
    }

    // --- Supply Item Edit --------------------------------------------------

    private void openSupplyItemEdit(Player player, String businessType, String itemId) {
        String display = module.supplyShop().itemDisplay(businessType, itemId);
        double price = module.supplyShop().itemPrice(businessType, itemId);
        int maxStock = module.supplyShop().itemMaxStock(businessType, itemId);
        Material rawMat = Material.matchMaterial(itemId);
        final Material mat = rawMat != null ? rawMat : Material.PAPER;

        var builder = ctx.gui().builder(GuiKit.title("Edit " + display), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the catalog"), (p, c) -> openSupplyCatalogEditor(p, businessType));
        builder.item(GuiKit.STATUS, ItemBuilder.of(mat)
                .name("&e" + display)
                .lore("&7ID: &f" + itemId,
                        "&7Price: &f" + GuiKit.fmt(price),
                        "&7Max stock: &f" + maxStock)
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.GOLD_INGOT, "&6Change price",
                "&7Current: &f" + GuiKit.fmt(price),
                "&7Click to set new price."), (p, c) -> {
            module.prompts().prompt(p, "&eType the new price (e.g. 150.00):", (pl, input) -> {
                try {
                    double newPrice = Double.parseDouble(input.trim());
                    module.supplyShop().addItem(businessType, itemId, display, mat, newPrice, maxStock);
                    ctx.notifications().msg(pl, "&aPrice updated to &f" + GuiKit.fmt(newPrice));
                } catch (NumberFormatException ex) {
                    ctx.notifications().warn(pl, "Invalid number.");
                }
                openSupplyItemEdit(pl, businessType, itemId);
            });
        });

        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.CHEST, "&6Change max stock",
                "&7Current: &f" + maxStock,
                "&7Click to set new max."), (p, c) -> {
            module.prompts().prompt(p, "&eType the new max stock:", (pl, input) -> {
                try {
                    int newMax = Integer.parseInt(input.trim());
                    module.supplyShop().addItem(businessType, itemId, display, mat, price, newMax);
                    ctx.notifications().msg(pl, "&aMax stock updated to &f" + newMax);
                } catch (NumberFormatException ex) {
                    ctx.notifications().warn(pl, "Invalid number.");
                }
                openSupplyItemEdit(pl, businessType, itemId);
            });
        });

        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.NAME_TAG, "&6Change display name",
                "&7Current: &f" + display,
                "&7Click to rename."), (p, c) -> {
            module.prompts().prompt(p, "&eType the new display name:", (pl, input) -> {
                String newName = input.trim();
                if (!newName.isEmpty()) {
                    module.supplyShop().addItem(businessType, itemId, newName, mat, price, maxStock);
                    ctx.notifications().msg(pl, "&aDisplay name updated to &f" + newName);
                }
                openSupplyItemEdit(pl, businessType, itemId);
            });
        });

        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&cRemove item",
                "&7Remove this item from",
                "&7the supply catalog."), (p, c) -> {
            module.supplyShop().removeItem(businessType, itemId);
            ctx.notifications().msg(p, "&cRemoved &f" + display + " &cfrom the catalog.");
            openSupplyCatalogEditor(p, businessType);
        });

        builder.open(player);
    }

    // --- Restock All -------------------------------------------------------

    private void openRestockAll(Player player) {
        Draft draft = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        var builder = ctx.gui().builder(GuiKit.title("Restock All"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the admin dashboard"), (p, c) -> openAdminDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.CHEST, "&6Restock All",
                "&7Restock amount: &f" + draft.count,
                "&7Refill all supply catalogs",
                "&7by this amount."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_2, ItemBuilder.of(Material.GRAY_DYE)
                .name("&7Amount: &f" + draft.count)
                .lore("&7Left: &f+1", "&7Right: &f-1",
                        "&7Shift-left: &f+10", "&7Shift-right: &f-10")
                .build(), (p, c) -> {
            int step = c.isShiftClick() ? 10 : 1;
            int delta = (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) ? -step : step;
            draft.count = Math.max(1, Math.min(64, draft.count + delta));
            openRestockAll(p);
        });

        builder.item(GuiKit.ACTION_4, GuiKit.cta(Material.LIME_WOOL,
                "&aRestock " + draft.count + "x",
                "&7Restock every supply catalog",
                "&7by " + draft.count + " units."), (p, c) -> {
            module.supplyShop().restockAll(draft.count);
            drafts.remove(p.getUniqueId());
            ctx.notifications().msg(p, "&aAll supply catalogs restocked by &f"
                    + draft.count + " &aunits.");
            openAdminDashboard(p);
        });

        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to the admin dashboard."), (p, c) -> {
            drafts.remove(p.getUniqueId());
            openAdminDashboard(p);
        });

        builder.open(player);
    }

    private static final class Draft {
        int count = 1;
    }
}
