package net.vicemc.modules.government;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.modules.business.Business;
import net.vicemc.modules.business.BusinessManager;
import net.vicemc.modules.business.BusinessType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The government counter menu used BY a government employee to serve a
 * citizen: free business registration, license sales, government stock and
 * subsidy applications. Only employees (vicemc.government) can open it, and
 * every action targets the citizen being served. Usable only inside the
 * government region.
 */
public final class GovernmentCounterGui {

    private static final int PER_PAGE = 27;

    private final ViceModuleContext ctx;
    private final GovernmentModule module;
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();

    public GovernmentCounterGui(ViceModuleContext ctx, GovernmentModule module) {
        this.ctx = ctx;
        this.module = module;
    }

    // --- Pick a citizen to serve ------------------------------------------

    public void openPicker(Player employee) {
        if (!inGovernment(employee)) {
            return;
        }
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.removeIf(p -> p.getUniqueId().equals(employee.getUniqueId()));
        int pages = GuiKit.Pages.pages(online.size(), PER_PAGE);
        var builder = ctx.gui().builder(GuiKit.title("Who are you serving?"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.icon(Material.BOOK, "&6Serve a citizen",
                "&7Pick the citizen at your counter.",
                "&7Only the government region is used."), GuiKit.NONE);
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.PLAYER_HEAD,
                "&6Citizens online", "&7" + online.size() + " player(s)."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Player> slice = GuiKit.Pages.slice(online, 1, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Player target = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.playerHead(target.getName(),
                    "&e" + target.getName(),
                    "&7Balance: &f" + GuiKit.fmt(ctx.economy().balance(target.getUniqueId())),
                    "&7Licenses: &f" + module.businessModule().manager().licenses(target.getUniqueId()).size(),
                    "&aClick to serve."), (p, c) -> openCounter(p, target.getUniqueId()));
        }
        builder.open(employee);
    }

    public void openCounter(Player employee, UUID target) {
        if (!inGovernment(employee)) {
            return;
        }
        BusinessManager manager = module.businessModule().manager();
        int licenses = manager.licenses(target).size();
        int owned = manager.byOwner(target).size();

        var builder = ctx.gui().builder(GuiKit.title("Serving: " + nameOf(target)), 6);
        GuiKit.frame(builder);
        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&6How it works",
                "&7You are serving &f" + nameOf(target) + "&7.",
                "&7Registration, licenses, wholesale",
                "&7stock and subsidy support."), GuiKit.NONE);
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.GOLD_BLOCK)
                .name("&6Serving " + nameOf(target))
                .lore("&7Businesses: &f" + owned,
                        "&7Licenses held: &f" + licenses,
                        "&7Citizen balance: &f" + GuiKit.fmt(ctx.economy().balance(target)),
                        "&7Treasury: &f" + GuiKit.fmt(module.treasury()))
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.CHEST, "&aRegister a business",
                "&7Register a business for them,",
                "&7free of charge."), (p, c) -> openRegister(p, target, 1));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.BOOK, "&6Sell a license",
                "&7Buy a license for them. Costs",
                "&7$2k-10k depending on the type,",
                "&7paid from their balance."), (p, c) -> openLicenseApply(p, target, 1));
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.NAME_TAG, "&6Their licenses",
                "&7Review which licenses they",
                "&7currently hold."), (p, c) -> openMyLicenses(p, target));
        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.EMERALD, "&6Government stock",
                "&7Buy wholesale stock for their",
                "&7licensed businesses."), (p, c) -> openStock(p, target, 1));
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.GOLD_INGOT, "&6Apply for a subsidy",
                "&7Request government support",
                "&7for one of their businesses."), (p, c) -> openSubsidyBusiness(p, target, 1));

        builder.open(employee);
    }

    // --- Business registration --------------------------------------------

    private void openRegister(Player employee, UUID target, int page) {
        if (!inGovernment(employee)) {
            return;
        }
        BusinessManager manager = module.businessModule().manager();
        List<String> types = new ArrayList<>();
        for (BusinessType type : BusinessType.values()) {
            types.add(type.name());
        }
        int pages = GuiKit.Pages.pages(types.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Register for " + nameOf(target)), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the counter menu"), (p, c) -> openCounter(p, target));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.CHEST, "&aRegister a business",
                "&7Registration is free. Some types",
                "&7need a license first."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<String> slice = GuiKit.Pages.slice(types, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            BusinessType type = BusinessType.valueOf(slice.get(i));
            boolean license = module.businessModule().businessConfig().getStringList("license-required").contains(type.name());
            boolean owned = manager.ownedOfType(target, type.name()) != null;
            boolean banned = manager.isBusinessBanned(target);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(typeMat(type))
                    .name("&e" + type.display())
                    .lore(license ? "&7Requires a government license." : "&aNo license required.",
                            owned ? "&cThey already own one." : "&aClick to register for them.",
                            banned ? "&4Banned from starting businesses." : "")
                    .build(), (p, c) -> {
                if (banned) {
                    ctx.notifications().warn(p, nameOf(target) + " is banned from starting businesses: "
                            + manager.banReason(target));
                    return;
                }
                if (owned) {
                    ctx.notifications().warn(p, nameOf(target) + " already owns a " + type.display() + ".");
                    return;
                }
                if (license && !manager.hasLicense(target, type.name())) {
                    ctx.notifications().warn(p, "A " + type.display() + " requires a government license. "
                            + "Sell them the license first.");
                    return;
                }
                askBusinessName(p, target, type);
            });
        }
        addPaging(builder, employee, safe, pages, (p, next) -> openRegister(p, target, next));
        builder.open(employee);
    }

    private void askBusinessName(Player employee, UUID target, BusinessType type) {
        module.prompts().prompt(employee,
                "&eName the &f" + type.display() + "&e for " + nameOf(target)
                        + " (max 24 characters), or type &6cancel&e.",
                (p, name) -> {
                    if (name.equalsIgnoreCase("cancel")) {
                        ctx.notifications().msg(p, "&7Registration cancelled.");
                        openCounter(p, target);
                        return;
                    }
                    if (name.isBlank() || name.length() > 24) {
                        ctx.notifications().warn(p, "Name must be 1-24 characters. Try again or type cancel.");
                        askBusinessName(p, target, type);
                        return;
                    }
                    module.businessModule().createBusinessFor(p, target, type, name.trim());
                    openCounter(p, target);
                });
    }

    // --- License application ----------------------------------------------

    private void openLicenseApply(Player employee, UUID target, int page) {
        if (!inGovernment(employee)) {
            return;
        }
        BusinessManager manager = module.businessModule().manager();
        List<String> types = new ArrayList<>();
        for (BusinessType type : BusinessType.values()) {
            types.add(type.name());
        }
        int pages = GuiKit.Pages.pages(types.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Sell a license to " + nameOf(target)), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the counter menu"), (p, c) -> openCounter(p, target));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.BOOK, "&6Sell a license",
                "&7Licenses cost between $2,000 and $10,000",
                "&7depending on the type. Click one to",
                "&7sell it, charged to their balance."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<String> slice = GuiKit.Pages.slice(types, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            BusinessType type = BusinessType.valueOf(slice.get(i));
            boolean has = manager.hasLicense(target, type.name());
            boolean required = module.businessModule().businessConfig().getStringList("license-required").contains(type.name());
            double cost = module.licenseCost(type.name());
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(has ? Material.LIME_DYE : Material.GRAY_DYE)
                    .name((has ? "&a" : "&7") + type.display())
                    .lore(has ? "&7Licensed. Nothing to do."
                            : (required ? "&7Required to register this type. " : "&7Optional license. ")
                                    + "&7Cost: &f" + GuiKit.fmt(cost) + "&7. &aClick to sell.")
                    .build(), (p, c) -> {
                if (has) {
                    ctx.notifications().msg(p, nameOf(target) + " already holds a " + type.display() + " license.");
                    return;
                }
                module.sellLicenseTo(p, target, type.name());
                openLicenseApply(p, target, 1);
            });
        }
        addPaging(builder, employee, safe, pages, (p, next) -> openLicenseApply(p, target, next));
        builder.open(employee);
    }

    private void openMyLicenses(Player employee, UUID target) {
        if (!inGovernment(employee)) {
            return;
        }
        BusinessManager manager = module.businessModule().manager();
        var builder = ctx.gui().builder(GuiKit.title("Licenses of " + nameOf(target)), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the counter menu"), (p, c) -> openCounter(p, target));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.NAME_TAG, "&6Their licenses",
                "&7Licenses held: &f" + manager.licenses(target).size(),
                "&7Green means they are licensed."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        BusinessType[] types = BusinessType.values();
        for (int i = 0; i < types.length; i++) {
            BusinessType type = types[i];
            boolean has = manager.hasLicense(target, type.name());
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(has ? Material.LIME_DYE : Material.GRAY_DYE)
                    .name((has ? "&a" : "&7") + type.display())
                    .lore(has ? "&aLicensed." : "&7Not licensed.")
                    .build(), GuiKit.NONE);
        }
        builder.open(employee);
    }

    // --- Government stock -------------------------------------------------

    private void openStock(Player employee, UUID target, int page) {
        if (!inGovernment(employee)) {
            return;
        }
        List<String> stocks = new ArrayList<>(module.businessModule().production().stockIds());
        int pages = GuiKit.Pages.pages(stocks.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Stock for " + nameOf(target)), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the counter menu"), (p, c) -> openCounter(p, target));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.EMERALD, "&6Government stock",
                "&7Wholesale items for their licensed",
                "&7businesses."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<String> slice = GuiKit.Pages.slice(stocks, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            String stockId = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, stockItem(stockId), (p, c) -> openBuyStock(p, target, stockId));
        }
        addPaging(builder, employee, safe, pages, (p, next) -> openStock(p, target, next));
        builder.open(employee);
    }

    private void openBuyStock(Player employee, UUID target, String stockId) {
        if (!inGovernment(employee)) {
            return;
        }
        Draft draft = drafts.computeIfAbsent(employee.getUniqueId(), k -> new Draft());
        ConfigurationSection stock = module.businessModule().businessConfig().getSection("government-stock." + stockId);
        double wholesale = stock == null ? 0 : stock.getDouble("wholesale", 0);
        boolean canBuy = module.businessModule().production().canBuyStockFor(target, stockId);
        var builder = ctx.gui().builder(GuiKit.title("Buy stock for " + nameOf(target)), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the stock menu"), (p, c) -> openStock(p, target, 1));
        builder.item(GuiKit.STATUS, stockItem(stockId), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_2, ItemBuilder.of(Material.GRAY_DYE)
                .name("&7Count: &f" + draft.count)
                .lore("&7Left: &f+1", "&7Right: &f-1",
                        "&7Shift-left: &f+10", "&7Shift-right: &f-10")
                .build(), (p, c) -> {
            int step = c.isShiftClick() ? 10 : 1;
            int delta = (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) ? -step : step;
            draft.count = Math.max(1, Math.min(64, draft.count + delta));
            openBuyStock(p, target, stockId);
        });
        if (canBuy) {
            builder.item(GuiKit.ACTION_4, GuiKit.cta(Material.EMERALD, "&aBuy " + draft.count + "x",
                    "&7Cost to citizen: &f" + GuiKit.fmt(wholesale * draft.count)), (p, c) -> {
                module.businessModule().production().buyStockFor(p, target, stockId, draft.count);
                drafts.remove(p.getUniqueId());
                openStock(p, target, 1);
            });
        } else {
            builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.GRAY_DYE, "&7Locked",
                    "&7They need a licensed business of",
                    "&7the required type."), GuiKit.NONE);
        }
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to the stock menu."), (p, c) -> openStock(p, target, 1));
        builder.open(employee);
    }

    // --- Subsidy applications ---------------------------------------------

    private void openSubsidyBusiness(Player employee, UUID target, int page) {
        if (!inGovernment(employee)) {
            return;
        }
        List<Business> owned = new ArrayList<>(module.businessModule().manager().byOwner(target));
        int pages = GuiKit.Pages.pages(owned.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Subsidy for " + nameOf(target)), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the counter menu"), (p, c) -> openCounter(p, target));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.GOLD_INGOT, "&6Apply for a subsidy",
                owned.isEmpty() ? "&7They own no businesses yet."
                        : "&7Pick one of their businesses."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Business> slice = GuiKit.Pages.slice(owned, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Business business = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.CHEST)
                    .name("&e" + business.name)
                    .lore("&7Type: &f" + business.type,
                            "&7ID: &f#" + business.id,
                            "&aClick to apply.").build(),
                    (p, c) -> openSubsidyAmount(p, target, business.id));
        }
        addPaging(builder, employee, safe, pages, (p, next) -> openSubsidyBusiness(p, target, next));
        builder.open(employee);
    }

    private void openSubsidyAmount(Player employee, UUID target, int businessId) {
        if (!inGovernment(employee)) {
            return;
        }
        Business business = module.businessModule().manager().byId(businessId).orElse(null);
        if (business == null) {
            openSubsidyBusiness(employee, target, 1);
            return;
        }
        Draft draft = drafts.computeIfAbsent(employee.getUniqueId(), k -> new Draft());
        draft.amount = Math.max(module.govConfig().getInt("subsidy-min", 20000), draft.amount);

        var builder = ctx.gui().builder(GuiKit.title("Subsidy: " + business.name), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("their businesses"), (p, c) -> openSubsidyBusiness(p, target, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.CHEST)
                .name("&e" + business.name)
                .lore("&7Type: &f" + business.type,
                        "&7Owner: &f" + nameOf(business.owner),
                        "&7Goods are granted if approved.",
                        "&7Amount requested below.").build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, ItemBuilder.of(Material.GOLD_INGOT)
                .name("&6Requested: &f" + GuiKit.fmt(draft.amount))
                .lore("&7Left: &f+5,000", "&7Right: &f-5,000",
                        "&7Shift-left: &f+25,000", "&7Shift-right: &f-25,000",
                        "&7Min: &f" + GuiKit.fmt(module.govConfig().getInt("subsidy-min", 20000)),
                        "&7Max: &f" + GuiKit.fmt(module.govConfig().getInt("subsidy-max", 40000)))
                .build(), (p, c) -> {
            int step = c.isShiftClick() ? 25_000 : 5_000;
            int delta = (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) ? -step : step;
            draft.amount = Math.max(module.govConfig().getInt("subsidy-min", 20000),
                    Math.min(module.govConfig().getInt("subsidy-max", 40000), draft.amount + delta));
            openSubsidyAmount(p, target, businessId);
        });
        builder.item(GuiKit.ACTION_3, GuiKit.cta(Material.GREEN_WOOL, "&aContinue",
                "&7Enter a reason for this",
                "&7subsidy request."), (p, c) -> askSubsidyReason(p, target, businessId, draft.amount));
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to their businesses."), (p, c) -> openSubsidyBusiness(p, target, 1));
        builder.open(employee);
    }

    private void askSubsidyReason(Player employee, UUID target, int businessId, double amount) {
        module.prompts().prompt(employee,
                "&eEnter a reason for the subsidy request in chat, or type &6cancel&e.",
                (p, reason) -> {
                    if (reason.equalsIgnoreCase("cancel")) {
                        ctx.notifications().msg(p, "&7Application cancelled.");
                        openCounter(p, target);
                        return;
                    }
                    module.submitSubsidyRequestFor(p, target, businessId, amount, reason.trim());
                    drafts.remove(p.getUniqueId());
                    openCounter(p, target);
                });
    }

    // --- Helpers ----------------------------------------------------------

    private boolean inGovernment(Player player) {
        if (ctx.regions().isInside(player.getLocation(), module.regionTag())) {
            return true;
        }
        ctx.notifications().warn(player, "&cYou must be inside the government building to use this.");
        return false;
    }

    private ItemStack stockItem(String stockId) {
        ConfigurationSection stock = module.businessModule().businessConfig().getSection("government-stock." + stockId);
        if (stock == null) {
            return ItemBuilder.of(Material.GRAY_DYE).name("&7" + stockId).build();
        }
        String display = stock.getString("display", stockId);
        double wholesale = stock.getDouble("wholesale", 0);
        double retail = stock.getDouble("retail", 0);
        Material mat = Material.matchMaterial(stock.getString("item", "SADDLE"));
        return ItemBuilder.of(mat == null ? Material.SADDLE : mat)
                .name("&e" + display)
                .lore("&7Wholesale: &f" + GuiKit.fmt(wholesale),
                        "&7Retail: &f" + GuiKit.fmt(retail),
                        "&7Requires: &f" + String.join(", ", stock.getStringList("requires")),
                        "&aClick for more options.")
                .build();
    }

    private Material typeMat(BusinessType type) {
        return switch (type) {
            case FARM -> Material.WHEAT;
            case FACTORY -> Material.CRAFTING_TABLE;
            case DEALERSHIP -> Material.SADDLE;
            case SHOP -> Material.CHEST;
            case FOOD_COMPANY -> Material.COOKED_BEEF;
            case JEWELRY_STORE -> Material.DIAMOND;
            case BANK -> Material.GOLD_INGOT;
            case MINE -> Material.DIAMOND_PICKAXE;
        };
    }

    private String nameOf(UUID uuid) {
        if (uuid == null) {
            return "unknown";
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    private void addPaging(GUIService.GuiBuilder builder, Player player, int page, int pages, PagingAction action) {
        if (pages <= 1) {
            return;
        }
        builder.item(GuiKit.PAGE_PREV, page > 1 ? GuiKit.prevPage(page) : GuiKit.pageGap(),
                (p, c) -> {
                    if (page > 1) {
                        action.accept(p, page - 1);
                    }
                });
        builder.item(GuiKit.PAGE_INDICATOR, GuiKit.pageIndicator(page, pages), GuiKit.NONE);
        builder.item(GuiKit.PAGE_NEXT, page < pages ? GuiKit.nextPage(page) : GuiKit.pageGap(),
                (p, c) -> {
                    if (page < pages) {
                        action.accept(p, page + 1);
                    }
                });
    }

    @FunctionalInterface
    private interface PagingAction {
        void accept(Player player, int page);
    }

    private static final class Draft {
        int count = 1;
        double amount;
    }
}
