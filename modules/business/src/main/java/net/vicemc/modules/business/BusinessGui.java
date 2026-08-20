package net.vicemc.modules.business;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
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
import java.util.function.Consumer;

/**
 * Business menu: owned business overview, creation with license checks,
 * factory crafting and selling, farm harvest/sell, government stock and
 * dealership sales - all in the dark modern menu style.
 */
public final class BusinessGui {

    private static final int PER_PAGE = 27;

    private final ViceModuleContext ctx;
    private final BusinessModule module;
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();

    public BusinessGui(ViceModuleContext ctx, BusinessModule module) {
        this.ctx = ctx;
        this.module = module;
    }

    public void openDashboard(Player player) {
        UUID me = player.getUniqueId();
        List<Business> owned = module.manager().byOwner(me);
        long licensed = owned.stream().filter(b -> b.licensed).count();
        boolean banned = module.manager().isBusinessBanned(me);

        var builder = ctx.gui().builder(GuiKit.title("Business"), 6);
        GuiKit.frame(builder);
        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&6How it works",
                "&7Own businesses, hire employees and",
                "&7run production chains. Factories craft,",
                "&7farms harvest, shops restock and",
                "&7dealerships sell vehicles."), GuiKit.NONE);
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.CHEST)
                .name("&6Your businesses")
                .lore("&7Owned: &f" + owned.size() + " &7(&a" + licensed + " licensed&7)",
                        "&7Balance: &a" + GuiKit.fmt(ctx.economy().balance(me)),
                        banned ? "&4Banned from starting businesses: " + module.manager().banReason(me)
                                : "&7Business operations active.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.CHEST, "&6My businesses",
                "&7Review and manage everything",
                "&7you own."), (p, c) -> openMyBusinesses(p, 1));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.GOLD_BLOCK, "&6Register at the government",
                "&7Businesses are registered by the",
                "&7government. Visit a government",
                "&7employee at the government building."), (p, c) -> {
            p.closeInventory();
            ctx.notifications().msg(p, "&eBusinesses are registered at the government building. "
                    + "Visit a government employee there to register.");
        });
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.CRAFTING_TABLE, "&6Factory",
                "&7Craft products and sell them",
                "&7to the government."), (p, c) -> openRecipes(p, 1));
        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.WHEAT, "&eFarm",
                "&7Harvest daily produce and",
                "&7sell it to the government."), (p, c) -> openFarm(p));
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.EMERALD, "&6Government stock",
                "&7Buy wholesale stock for",
                "&7your licensed business."), (p, c) -> openStock(p, 1));

        boolean hasSupplyShop = false;
        for (BusinessType bt : BusinessType.values()) {
            if (module.supplyShop().hasCatalog(bt.name())) {
                Business b = module.manager().ownedOfType(me, bt.name());
                if (b != null && b.licensed) {
                    hasSupplyShop = true;
                    break;
                }
            }
        }
        if (hasSupplyShop) {
            builder.item(22, GuiKit.cta(Material.EMERALD, "&6Supply Shop",
                    "&7Buy supplies for your",
                    "&7licensed businesses."), (p, c) -> openSupplyShop(p));
        }

        builder.open(player);
    }

    // --- My businesses ----------------------------------------------------

    private void openMyBusinesses(Player player, int page) {
        List<Business> owned = new ArrayList<>(module.manager().byOwner(player.getUniqueId()));
        int pages = GuiKit.Pages.pages(owned.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("My businesses"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the business menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.CHEST, "&6Your businesses",
                "&7" + owned.size() + " business(es).",
                "&7Click one to manage it."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Business> slice = GuiKit.Pages.slice(owned, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Business business = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, businessItem(business, "&aClick to manage."),
                    (p, c) -> openBusinessDetail(p, business.id));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openMyBusinesses(p, next));
        builder.open(player);
    }

    private void openBusinessDetail(Player player, int businessId) {
        Business business = module.manager().byId(businessId).orElse(null);
        if (business == null) {
            ctx.notifications().warn(player, "That business no longer exists.");
            openMyBusinesses(player, 1);
            return;
        }
        boolean owner = business.isOwner(player.getUniqueId());
        String type = business.typeEnum() == null ? business.type : business.typeEnum().display();

        var builder = ctx.gui().builder(GuiKit.title(business.name), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("your businesses"), (p, c) -> openMyBusinesses(p, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.CHEST)
                .name("&6" + business.name)
                .lore("&7Type: &f" + type,
                        "&7ID: &f#" + business.id,
                        "&7Licensed: " + (business.licensed ? "&aYes" : "&cNo"),
                        "&7Employees: &f" + business.employees.size(),
                        business.stock.isEmpty() ? "" : "&7Stock: &f" + business.stock.toString())
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.PLAYER_HEAD, "&eEmployees",
                "&7" + business.employees.size() + " employee(s).",
                "&7Add or remove staff."), (p, c) -> openEmployees(p, business.id, 1));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.CHEST, "&6Company Overview",
                "&7View business details,",
                "&7work hours and payroll."),
                (p, c) -> openCompanyOverview(p, business.id));
        if (owner && business.licensed && module.supplyShop().hasCatalog(business.type)) {
            builder.item(GuiKit.ACTION_3, GuiKit.cta(Material.EMERALD, "&6Supply Shop",
                    "&7Buy supplies for",
                    "&7this business."), (p, c) -> openSupplyItems(p, business.type, 1));
        }
        if (owner) {
            builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.WRITABLE_BOOK, "&6Roles",
                    "&7Manage company roles",
                    "&7and permissions."), (p, c) -> openRoles(p, business.id));
            builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.GOLD_INGOT, "&6Payroll",
                    "&7View and pay",
                    "&7employee salaries."), (p, c) -> openPayroll(p, business.id));
        }
        if (owner) {
            builder.item(GuiKit.GRID_FIRST, GuiKit.icon(Material.GOLD_INGOT, "&6Sell products",
                    "&7Sell factory products to",
                    "&7the government."), (p, c) -> openSellProduct(p, business.id, 1));
            builder.item(GuiKit.GRID_FIRST + 1, GuiKit.icon(Material.EMERALD, "&6Restock",
                    "&7Buy government wholesale",
                    "&7stock for this business."), (p, c) -> openStock(p, 1));
            if ("DEALERSHIP".equals(business.type)) {
                builder.item(GuiKit.GRID_FIRST + 2, GuiKit.icon(Material.SADDLE, "&aSell vehicle",
                        "&7Sell a vehicle from stock",
                        "&7to a customer."), (p, c) -> openDealerCustomer(p, business.id, 1));
            }
            builder.item(GuiKit.GRID_FIRST + 3, GuiKit.icon(Material.NAME_TAG, "&6Assign Role",
                    "&7Assign a role to",
                    "&7an employee."), (p, c) -> openAssignRole(p, business.id));
        }
        builder.open(player);
    }

    private void openEmployees(Player player, int businessId, int page) {
        Business business = module.manager().byId(businessId).orElse(null);
        if (business == null) {
            openMyBusinesses(player, 1);
            return;
        }
        List<UUID> employees = new ArrayList<>(business.employees);
        int pages = GuiKit.Pages.pages(employees.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Employees"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the business"), (p, c) -> openBusinessDetail(p, businessId));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.PLAYER_HEAD, "&eEmployees",
                "&7" + employees.size() + " employee(s).",
                "&7Click a head to remove."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<UUID> slice = GuiKit.Pages.slice(employees, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            UUID employee = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.playerHead(nameOf(employee),
                    "&e" + nameOf(employee),
                    "&cClick to remove."), (p, c) -> {
                module.removeEmployee(p, businessId, employee);
                openEmployees(p, businessId, 1);
            });
        }
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.NAME_TAG, "&aAdd employee",
                "&7Pick an online player to",
                "&7add to this business."), (p, c) -> {
            openPlayerPicker(p, "Add employee", 1,
                    "&aAdd employee", Material.NAME_TAG,
                    "&7Click a player to hire.",
                    "&7They can operate this business.",
                    () -> openEmployees(p, businessId, 1),
                    target -> {
                        module.addEmployee(p, businessId, target);
                        openEmployees(p, businessId, 1);
                    });
        });
        addPaging(builder, player, safe, pages, (p, next) -> openEmployees(p, businessId, next));
        builder.open(player);
    }

    // --- Factory ----------------------------------------------------------

    private void openRecipes(Player player, int page) {
        List<String> recipes = new ArrayList<>(module.production().recipeIds());
        int pages = GuiKit.Pages.pages(recipes.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Factory recipes"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the business menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.CRAFTING_TABLE, "&6Factory recipes",
                "&7" + recipes.size() + " recipe(s).",
                "&7Craft, then sell to the government."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<String> slice = GuiKit.Pages.slice(recipes, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            String recipeId = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, recipeItem(recipeId), (p, c) -> openCraft(p, recipeId));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openRecipes(p, next));
        builder.open(player);
    }

    private void openCraft(Player player, String recipeId) {
        Draft draft = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        var builder = ctx.gui().builder(GuiKit.title("Craft " + module.production().recipeDisplay(recipeId)), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the recipes"), (p, c) -> openRecipes(p, 1));
        builder.item(GuiKit.STATUS, recipeItem(recipeId), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_2, ItemBuilder.of(Material.GRAY_DYE)
                .name("&7Count: &f" + draft.count)
                .lore("&7Left: &f+1", "&7Right: &f-1",
                        "&7Shift-left: &f+10", "&7Shift-right: &f-10")
                .build(), (p, c) -> {
            int step = c.isShiftClick() ? 10 : 1;
            int delta = (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) ? -step : step;
            draft.count = Math.max(1, Math.min(64, draft.count + delta));
            openCraft(p, recipeId);
        });
        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.GREEN_WOOL, "&aCraft " + draft.count + "x",
                "&7Consumes the recipe inputs from",
                "&7your inventory."), (p, c) -> {
            module.production().craft(p, recipeId, draft.count);
            drafts.remove(p.getUniqueId());
            openRecipes(p, 1);
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to the recipes."), (p, c) -> openRecipes(p, 1));
        builder.open(player);
    }

    private void openSellProduct(Player player, int businessId, int page) {
        List<String> recipes = new ArrayList<>(module.production().recipeIds());
        int pages = GuiKit.Pages.pages(recipes.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Sell products"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the business"), (p, c) -> openBusinessDetail(p, businessId));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.GOLD_INGOT, "&6Sell products",
                "&7Sell tagged products from your",
                "&7inventory to the government."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<String> slice = GuiKit.Pages.slice(recipes, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            String recipeId = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, recipeItem(recipeId), (p, c) -> openSellConfirm(p, businessId, recipeId));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openSellProduct(p, businessId, next));
        builder.open(player);
    }

    private void openSellConfirm(Player player, int businessId, String recipeId) {
        Draft draft = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        double price = module.businessConfig().getDouble("factory-recipes." + recipeId + ".government-price", 0);
        var builder = ctx.gui().builder(GuiKit.title("Sell " + module.production().recipeDisplay(recipeId)), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the sell menu"), (p, c) -> openSellProduct(p, businessId, 1));
        builder.item(GuiKit.STATUS, recipeItem(recipeId), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_2, ItemBuilder.of(Material.GRAY_DYE)
                .name("&7Count: &f" + draft.count)
                .lore("&7Left: &f+1", "&7Right: &f-1",
                        "&7Shift-left: &f+10", "&7Shift-right: &f-10")
                .build(), (p, c) -> {
            int step = c.isShiftClick() ? 10 : 1;
            int delta = (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) ? -step : step;
            draft.count = Math.max(1, Math.min(64, draft.count + delta));
            openSellConfirm(p, businessId, recipeId);
        });
        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.GOLD_INGOT, "&aSell " + draft.count + "x",
                "&7Estimated: &f" + GuiKit.fmt(price * draft.count)), (p, c) -> {
            module.production().sellProduct(p, recipeId, draft.count);
            drafts.remove(p.getUniqueId());
            openDashboard(p);
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to the business menu."), (p, c) -> openDashboard(p));
        builder.open(player);
    }

    // --- Farm -------------------------------------------------------------

    private void openFarm(Player player) {
        var builder = ctx.gui().builder(GuiKit.title("Farm"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the business menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.WHEAT, "&eFarm",
                "&7Harvest once per day and sell",
                "&7produce to the government.",
                "&7Requires a FARM business."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.WHEAT, "&aHarvest",
                "&7Collect today's farm produce."), (p, c) -> {
            module.production().farmHarvest(p);
            openFarm(p);
        });
        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.GOLD_INGOT, "&aSell produce",
                "&7Sell all farm produce in your",
                "&7inventory."), (p, c) -> {
            module.production().farmSell(p);
            openFarm(p);
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Back",
                "&7Return to the business menu."), (p, c) -> openDashboard(p));
        builder.open(player);
    }

    // --- Government stock -------------------------------------------------

    private void openStock(Player player, int page) {
        List<String> stocks = new ArrayList<>(module.production().stockIds());
        int pages = GuiKit.Pages.pages(stocks.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Government stock"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the business menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.EMERALD, "&6Government stock",
                "&7" + stocks.size() + " stock item(s).",
                "&7Requires a matching licensed business."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<String> slice = GuiKit.Pages.slice(stocks, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            String stockId = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, stockItem(stockId), (p, c) -> openBuyStock(p, stockId));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openStock(p, next));
        builder.open(player);
    }

    private void openBuyStock(Player player, String stockId) {
        Draft draft = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        ConfigurationSection stock = module.businessConfig().getSection("government-stock." + stockId);
        double wholesale = stock == null ? 0 : stock.getDouble("wholesale", 0);
        boolean canBuy = module.production().canBuyStock(player, stockId);
        var builder = ctx.gui().builder(GuiKit.title("Buy stock"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the stock menu"), (p, c) -> openStock(p, 1));
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
            openBuyStock(p, stockId);
        });
        if (canBuy) {
            builder.item(GuiKit.ACTION_4, GuiKit.cta(Material.EMERALD, "&aBuy " + draft.count + "x",
                    "&7Cost: &f" + GuiKit.fmt(wholesale * draft.count)), (p, c) -> {
                module.production().buyStock(p, stockId, draft.count);
                drafts.remove(p.getUniqueId());
                openStock(p, 1);
            });
        } else {
            builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.GRAY_DYE, "&7Locked",
                    "&7You need a licensed business of",
                    "&7the required type."), GuiKit.NONE);
        }
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to the stock menu."), (p, c) -> openStock(p, 1));
        builder.open(player);
    }

    // --- Dealership -------------------------------------------------------

    private void openDealerCustomer(Player player, int businessId, int page) {
        openPlayerPicker(player, "Pick a customer", page,
                "&aSell vehicle", Material.SADDLE,
                "&7Choose the customer to",
                "&7sell a vehicle to.",
                () -> openBusinessDetail(player, businessId),
                target -> openDealerVehicle(player, businessId, target, 1));
    }

    private void openDealerVehicle(Player player, int businessId, UUID customer, int page) {
        List<String> vehicles = new ArrayList<>();
        for (String id : module.production().stockIds()) {
            ConfigurationSection stock = module.businessConfig().getSection("government-stock." + id);
            if (stock != null && stock.getStringList("requires").contains("DEALERSHIP")) {
                vehicles.add(id);
            }
        }
        int pages = GuiKit.Pages.pages(vehicles.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Pick a vehicle"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the customer picker"), (p, c) -> openDealerCustomer(p, businessId, 1));
        builder.item(GuiKit.STATUS, GuiKit.playerHead(nameOf(customer),
                "&eCustomer: " + nameOf(customer),
                "&7Balance: &f" + GuiKit.fmt(ctx.economy().balance(customer))), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<String> slice = GuiKit.Pages.slice(vehicles, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            String stockId = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, stockItem(stockId), (p, c) -> {
                Player customerPlayer = Bukkit.getPlayer(customer);
                if (customerPlayer != null) {
                    module.production().dealerSell(p, customerPlayer, stockId);
                } else {
                    p.sendMessage(net.vicemc.api.util.Text.color("&cThat customer is offline."));
                }
                openBusinessDetail(p, businessId);
            });
        }
        addPaging(builder, player, safe, pages, (p, next) -> openDealerVehicle(p, businessId, customer, next));
        builder.open(player);
    }

    // --- Player picker ----------------------------------------------------

    private void openPlayerPicker(Player player, String title, int page, String name,
                                  Material icon, String descLine1, String descLine2,
                                  Runnable back, Consumer<UUID> onPick) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.removeIf(p -> p.getUniqueId().equals(player.getUniqueId()));
        int pages = GuiKit.Pages.pages(online.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title(title), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the previous menu"), (p, c) -> back.run());
        builder.item(GuiKit.STATUS, GuiKit.icon(icon, name, descLine1, descLine2), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Player> slice = GuiKit.Pages.slice(online, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Player target = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.playerHead(target.getName(),
                    "&e" + target.getName(),
                    "&7Balance: &f" + GuiKit.fmt(ctx.economy().balance(target.getUniqueId())),
                    "&7Click to select."), (p, c) -> onPick.accept(target.getUniqueId()));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openPlayerPicker(p, title, next, name,
                icon, descLine1, descLine2, back, onPick));
        builder.open(player);
    }

    // --- Supply Shop -------------------------------------------------------

    public void openSupplyShop(Player player) {
        UUID me = player.getUniqueId();
        var builder = ctx.gui().builder(GuiKit.title("Supply Shop"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the business menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.EMERALD, "&6Supply Shop",
                "&7Buy supplies for your",
                "&7licensed businesses."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        int slot = GuiKit.GRID_FIRST;
        for (BusinessType type : BusinessType.values()) {
            if (!module.supplyShop().hasCatalog(type.name())) {
                continue;
            }
            Business business = module.manager().ownedOfType(me, type.name());
            if (business == null || !business.licensed) {
                continue;
            }
            builder.item(slot, GuiKit.icon(Material.EMERALD_BLOCK, "&e" + type.display(),
                    "&7Business: &f" + business.name,
                    "&7Click to browse supplies."), (p, c) -> openSupplyItems(p, type.name(), 1));
            slot++;
            if (slot >= GuiKit.GRID_FIRST + GuiKit.GRID_SIZE) {
                break;
            }
        }
        if (slot == GuiKit.GRID_FIRST) {
            builder.item(22, GuiKit.icon(Material.GRAY_DYE, "&7No supply catalogs available",
                    "&7You need a licensed business",
                    "&7with an active supply catalog."), GuiKit.NONE);
        }
        builder.open(player);
    }

    private void openSupplyItems(Player player, String businessType, int page) {
        List<String> items = new ArrayList<>(module.supplyShop().catalogIds(businessType));
        int pages = GuiKit.Pages.pages(items.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title(businessType + " Supplies"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the supply shop"), (p, c) -> openSupplyShop(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.EMERALD, "&6" + businessType + " Supplies",
                "&7" + items.size() + " item(s).",
                "&7Click an item to buy."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<String> slice = GuiKit.Pages.slice(items, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            String itemId = slice.get(i);
            String display = module.supplyShop().itemDisplay(businessType, itemId);
            double price = module.supplyShop().itemPrice(businessType, itemId);
            Business biz = module.manager().ownedOfType(player.getUniqueId(), businessType);
            int stock = biz != null ? module.supplyShop().currentStock(biz, itemId) : 0;
            Material mat = Material.matchMaterial(itemId);
            if (mat == null) {
                mat = Material.PAPER;
            }
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(mat)
                    .name("&e" + display)
                    .lore("&7Price: &f" + GuiKit.fmt(price),
                            "&7Stock: &f" + stock,
                            "&aClick to buy.")
                    .build(), (p, c) -> openBuySupply(p, businessType, itemId));
        }
        addPaging(builder, player, safe, pages,
                (p, next) -> openSupplyItems(p, businessType, next));
        builder.open(player);
    }

    private void openBuySupply(Player player, String businessType, String itemId) {
        Draft draft = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        String display = module.supplyShop().itemDisplay(businessType, itemId);
        double price = module.supplyShop().itemPrice(businessType, itemId);
        Material mat = Material.matchMaterial(itemId);
        if (mat == null) {
            mat = Material.PAPER;
        }
        var builder = ctx.gui().builder(GuiKit.title("Buy " + display), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the supply items"), (p, c) -> openSupplyItems(p, businessType, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(mat)
                .name("&e" + display)
                .lore("&7Price per unit: &f" + GuiKit.fmt(price),
                        "&7Total cost: &f" + GuiKit.fmt(price * draft.count))
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_2, ItemBuilder.of(Material.GRAY_DYE)
                .name("&7Count: &f" + draft.count)
                .lore("&7Left: &f+1", "&7Right: &f-1",
                        "&7Shift-left: &f+10", "&7Shift-right: &f-10")
                .build(), (p, c) -> {
            int step = c.isShiftClick() ? 10 : 1;
            int delta = (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) ? -step : step;
            draft.count = Math.max(1, Math.min(64, draft.count + delta));
            openBuySupply(p, businessType, itemId);
        });
        builder.item(GuiKit.ACTION_4, GuiKit.cta(Material.EMERALD, "&aBuy " + draft.count + "x",
                "&7Cost: &f" + GuiKit.fmt(price * draft.count)), (p, c) -> {
            Business business = module.manager().ownedOfType(p.getUniqueId(), businessType);
            if (business == null) {
                ctx.notifications().warn(p, "No business of that type found.");
                drafts.remove(p.getUniqueId());
                openSupplyShop(p);
                return;
            }
            boolean success = module.supplyShop().buySupply(p, business, itemId, draft.count);
            if (success) {
                drafts.remove(p.getUniqueId());
                openSupplyItems(p, businessType, 1);
            } else {
                openBuySupply(p, businessType, itemId);
            }
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to the supply items."), (p, c) -> {
            drafts.remove(p.getUniqueId());
            openSupplyItems(p, businessType, 1);
        });
        builder.open(player);
    }

    // --- Company Overview --------------------------------------------------

    private void openCompanyOverview(Player player, int businessId) {
        Business business = module.manager().byId(businessId).orElse(null);
        if (business == null) {
            openMyBusinesses(player, 1);
            return;
        }
        boolean owner = business.isOwner(player.getUniqueId());
        String type = business.typeEnum() == null ? business.type : business.typeEnum().display();
        var builder = ctx.gui().builder(GuiKit.title("Company Overview"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the business"), (p, c) -> openBusinessDetail(p, businessId));
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        if (owner) {
            builder.item(GuiKit.STATUS, ItemBuilder.of(Material.CHEST)
                    .name("&6" + business.name)
                    .lore("&7Type: &f" + type,
                            "&7Owner: &f" + nameOf(business.owner),
                            "&7Employees: &f" + business.employees.size(),
                            "&7Roles: &f" + business.roles.size(),
                            "&7Licensed: " + (business.licensed ? "&aYes" : "&cNo"),
                            "&7Created: &f" + new java.text.SimpleDateFormat("yyyy-MM-dd")
                                    .format(new java.util.Date(business.createdAt)))
                    .build(), GuiKit.NONE);
            long totalSeconds = 0;
            var workTimes = module.workTime().getAllWorkTimes(businessId);
            for (long t : workTimes.values()) {
                totalSeconds += t;
            }
            double hours = totalSeconds / 3600.0;
            builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.CLOCK, "&6Work hours",
                    "&7Total: &f" + String.format(java.util.Locale.US, "%.1f", hours) + " hours",
                    "&7Across &f" + workTimes.size() + " &7employee(s)."), GuiKit.NONE);
            var payouts = module.salary().previewPayouts(businessId);
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.GOLD_INGOT, "&6Total payroll",
                    "&7" + payouts.size() + " employee(s) to pay.",
                    "&7Go to Payroll for details."), GuiKit.NONE);
        } else {
            String myRole = business.roleOf(player.getUniqueId());
            CompanyRole roleConfig = business.roleConfigOf(player.getUniqueId());
            builder.item(GuiKit.STATUS, ItemBuilder.of(Material.CHEST)
                    .name("&6" + business.name)
                    .lore("&7Type: &f" + type,
                            "&7Your role: &f" + (myRole != null ? myRole : "Unassigned"))
                    .build(), GuiKit.NONE);
            if (roleConfig != null) {
                builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.WRITABLE_BOOK,
                        "&6Your role: " + myRole,
                        "&7Hourly rate: &f" + GuiKit.fmt(roleConfig.hourlyRate),
                        "&7Flags: &f" + roleConfig.flagsSummary()), GuiKit.NONE);
            }
            long workSeconds = module.workTime().getWorkSeconds(player.getUniqueId(), businessId);
            double workHours = workSeconds / 3600.0;
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.CLOCK, "&6Your work time",
                    "&7Total: &f" + String.format(java.util.Locale.US, "%.1f", workHours) + " hours"),
                    GuiKit.NONE);
            if (roleConfig != null) {
                double pay = module.salary().calculatePay(business, player.getUniqueId());
                builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.GOLD_INGOT, "&6Your pay rate",
                        "&7Hourly: &f" + GuiKit.fmt(roleConfig.hourlyRate),
                        "&7Current earned: &f" + GuiKit.fmt(pay)), GuiKit.NONE);
            }
        }
        builder.open(player);
    }

    // --- Roles -------------------------------------------------------------

    private void openRoles(Player player, int businessId) {
        Business business = module.manager().byId(businessId).orElse(null);
        if (business == null) {
            openMyBusinesses(player, 1);
            return;
        }
        List<String> roleNames = new ArrayList<>(business.roles.keySet());
        int pages = GuiKit.Pages.pages(roleNames.size(), PER_PAGE);
        int safe = Math.min(1, pages);
        var builder = ctx.gui().builder(GuiKit.title("Roles"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the business"), (p, c) -> openBusinessDetail(p, businessId));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.WRITABLE_BOOK, "&6Company Roles",
                "&7" + roleNames.size() + " role(s).",
                "&7Click a role to edit."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<String> slice = GuiKit.Pages.slice(roleNames, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            String roleName = slice.get(i);
            CompanyRole role = business.roles.get(roleName);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.WRITABLE_BOOK)
                    .name("&e" + roleName)
                    .lore("&7Hourly: &f" + GuiKit.fmt(role.hourlyRate),
                            "&7Flags: &f" + role.flagsSummary(),
                            "&aClick to edit.")
                    .build(), (p, c) -> openRoleEditor(p, businessId, roleName));
        }
        builder.item(GuiKit.ACTION_5, GuiKit.cta(Material.LIME_WOOL, "&aCreate Role",
                "&7Create a new role",
                "&7for this business."), (p, c) -> openRoleEditor(p, businessId, null));
        addPaging(builder, player, safe, pages, (p, next) -> openRoles(p, businessId));
        builder.open(player);
    }

    private void openRoleEditor(Player player, int businessId, String roleName) {
        Business business = module.manager().byId(businessId).orElse(null);
        if (business == null) {
            openMyBusinesses(player, 1);
            return;
        }
        if (roleName == null) {
            module.prompts().prompt(player, "&eType the role name:", (pl, input) -> {
                String name = input.trim();
                if (name.isEmpty()) {
                    ctx.notifications().warn(pl, "Invalid name.");
                    openRoles(pl, businessId);
                    return;
                }
                if (business.roles.containsKey(name)) {
                    ctx.notifications().warn(pl, "A role with that name already exists.");
                    openRoles(pl, businessId);
                    return;
                }
                module.roleService().createRole(business, name, 10.0, false, false, false, false);
                module.manager().save();
                openRoleEditor(pl, businessId, name);
            });
            return;
        }
        CompanyRole role = business.roles.get(roleName);
        if (role == null) {
            openRoles(player, businessId);
            return;
        }
        var builder = ctx.gui().builder(GuiKit.title("Edit Role: " + roleName), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the roles"), (p, c) -> openRoles(p, businessId));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.WRITABLE_BOOK)
                .name("&e" + roleName)
                .lore("&7Hourly rate: &f" + GuiKit.fmt(role.hourlyRate),
                        "&7Hire: " + (role.canHire ? "&aYes" : "&cNo"),
                        "&7Fire: " + (role.canFire ? "&aYes" : "&cNo"),
                        "&7Set Salary: " + (role.canSetSalary ? "&aYes" : "&cNo"),
                        "&7Manage Supply: " + (role.canManageSupply ? "&aYes" : "&cNo"))
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, ItemBuilder.of(Material.GRAY_DYE)
                .name("&6Hourly rate: &f" + GuiKit.fmt(role.hourlyRate))
                .lore("&7Left: &f+$1.00", "&7Right: &f-$1.00",
                        "&7Shift-left: &f+$10.00", "&7Shift-right: &f-$10.00")
                .build(), (p, c) -> {
            int step = c.isShiftClick() ? 10 : 1;
            int delta = (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) ? -step : step;
            role.hourlyRate = Math.max(0, role.hourlyRate + delta);
            module.manager().save();
            openRoleEditor(p, businessId, roleName);
        });
        builder.item(GuiKit.ACTION_2, ItemBuilder.of(
                role.canHire ? Material.LIME_DYE : Material.RED_DYE)
                .name("&6Can Hire: " + (role.canHire ? "&aON" : "&cOFF"))
                .lore("&7Click to toggle.")
                .build(), (p, c) -> {
            role.canHire = !role.canHire;
            module.manager().save();
            openRoleEditor(p, businessId, roleName);
        });
        builder.item(GuiKit.ACTION_3, ItemBuilder.of(
                role.canFire ? Material.LIME_DYE : Material.RED_DYE)
                .name("&6Can Fire: " + (role.canFire ? "&aON" : "&cOFF"))
                .lore("&7Click to toggle.")
                .build(), (p, c) -> {
            role.canFire = !role.canFire;
            module.manager().save();
            openRoleEditor(p, businessId, roleName);
        });
        builder.item(GuiKit.ACTION_4, ItemBuilder.of(
                role.canSetSalary ? Material.LIME_DYE : Material.RED_DYE)
                .name("&6Can Set Salary: " + (role.canSetSalary ? "&aON" : "&cOFF"))
                .lore("&7Click to toggle.")
                .build(), (p, c) -> {
            role.canSetSalary = !role.canSetSalary;
            module.manager().save();
            openRoleEditor(p, businessId, roleName);
        });
        builder.item(GuiKit.ACTION_5, ItemBuilder.of(
                role.canManageSupply ? Material.LIME_DYE : Material.RED_DYE)
                .name("&6Can Manage Supply: " + (role.canManageSupply ? "&aON" : "&cOFF"))
                .lore("&7Click to toggle.")
                .build(), (p, c) -> {
            role.canManageSupply = !role.canManageSupply;
            module.manager().save();
            openRoleEditor(p, businessId, roleName);
        });
        builder.item(GuiKit.GRID_FIRST, GuiKit.cta(Material.LIME_WOOL, "&aSave",
                "&7Save and return to roles."), (p, c) -> {
            module.manager().save();
            openRoles(p, businessId);
        });
        builder.item(GuiKit.GRID_FIRST + 1, GuiKit.icon(Material.BARRIER, "&cDelete role",
                "&7Remove this role permanently.",
                "&7Employees will be unassigned."), (p, c) -> {
            module.roleService().deleteRole(business, roleName);
            module.manager().save();
            ctx.notifications().msg(p, "&cDeleted role &f" + roleName + "&c.");
            openRoles(p, businessId);
        });
        builder.open(player);
    }

    // --- Assign Role -------------------------------------------------------

    private void openAssignRole(Player player, int businessId) {
        Business business = module.manager().byId(businessId).orElse(null);
        if (business == null) {
            openMyBusinesses(player, 1);
            return;
        }
        List<UUID> employees = new ArrayList<>(business.employees);
        int pages = GuiKit.Pages.pages(employees.size(), PER_PAGE);
        int safe = Math.min(1, pages);
        var builder = ctx.gui().builder(GuiKit.title("Assign Role"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the business"), (p, c) -> openBusinessDetail(p, businessId));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.NAME_TAG, "&6Assign Role",
                "&7Pick an employee to assign",
                "&7a role to."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<UUID> slice = GuiKit.Pages.slice(employees, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            UUID employee = slice.get(i);
            String role = business.roleOf(employee);
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.playerHead(nameOf(employee),
                    "&e" + nameOf(employee),
                    "&7Current role: &f" + (role != null ? role : "None"),
                    "&aClick to assign."), (p, c) -> openPickRole(p, businessId, employee));
        }
        addPaging(builder, player, safe, pages,
                (p, next) -> openAssignRole(p, businessId));
        builder.open(player);
    }

    private void openPickRole(Player player, int businessId, UUID employee) {
        Business business = module.manager().byId(businessId).orElse(null);
        if (business == null) {
            openMyBusinesses(player, 1);
            return;
        }
        List<String> roleNames = new ArrayList<>(business.roles.keySet());
        var builder = ctx.gui().builder(GuiKit.title("Pick Role for " + nameOf(employee)), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the employee list"), (p, c) -> openAssignRole(p, businessId));
        builder.item(GuiKit.STATUS, GuiKit.playerHead(nameOf(employee),
                "&e" + nameOf(employee),
                "&7Current role: &f" + (business.roleOf(employee) != null
                        ? business.roleOf(employee) : "None")), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&cUnassign role",
                "&7Remove the current role"), (p, c) -> {
            module.roleService().unassignRole(business, employee);
            module.manager().save();
            ctx.notifications().msg(p, "&aRemoved role from &f" + nameOf(employee) + "&a.");
            openAssignRole(p, businessId);
        });
        for (int i = 0; i < roleNames.size(); i++) {
            String roleName = roleNames.get(i);
            CompanyRole role = business.roles.get(roleName);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.WRITABLE_BOOK)
                    .name("&e" + roleName)
                    .lore("&7Hourly: &f" + GuiKit.fmt(role.hourlyRate),
                            "&7Flags: &f" + role.flagsSummary(),
                            "&aClick to assign.")
                    .build(), (p, c) -> {
                module.roleService().assignRole(business, employee, roleName);
                module.manager().save();
                ctx.notifications().msg(p, "&aAssigned &f" + roleName + " &ato &f"
                        + nameOf(employee) + "&a.");
                openAssignRole(p, businessId);
            });
        }
        builder.open(player);
    }

    // --- Payroll -----------------------------------------------------------

    private void openPayroll(Player player, int businessId) {
        Business business = module.manager().byId(businessId).orElse(null);
        if (business == null) {
            openMyBusinesses(player, 1);
            return;
        }
        List<String> payouts = module.salary().previewPayouts(businessId);
        var builder = ctx.gui().builder(GuiKit.title("Payroll"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the business"), (p, c) -> openBusinessDetail(p, businessId));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.GOLD_INGOT, "&6Payroll",
                "&7" + payouts.size() + " employee(s) to pay.",
                "&7Preview of payouts:"), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        for (int i = 0; i < Math.min(payouts.size(), GuiKit.GRID_SIZE); i++) {
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.icon(Material.PAPER,
                    "&f" + payouts.get(i)), GuiKit.NONE);
        }
        builder.item(GuiKit.ACTION_4, GuiKit.cta(Material.GOLD_INGOT, "&aPay Salaries",
                "&7Pay all employee salaries now."), (p, c) -> {
            module.salary().paySalaries();
            ctx.notifications().msg(p, "&aAll salaries paid.");
            openBusinessDetail(p, businessId);
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to the business."), (p, c) -> openBusinessDetail(p, businessId));
        builder.open(player);
    }

    // --- Helpers ----------------------------------------------------------

    private ItemStack businessItem(Business business, String extra) {
        String type = business.typeEnum() == null ? business.type : business.typeEnum().display();
        return ItemBuilder.of(Material.CHEST)
                .name("&e" + business.name)
                .lore("&7Type: &f" + type,
                        "&7ID: &f#" + business.id,
                        "&7Licensed: " + (business.licensed ? "&aYes" : "&cNo"),
                        "&7Employees: &f" + business.employees.size(),
                        extra)
                .build();
    }

    private ItemStack recipeItem(String recipeId) {
        String display = module.production().recipeDisplay(recipeId);
        double price = module.businessConfig().getDouble("factory-recipes." + recipeId + ".government-price", 0);
        return ItemBuilder.of(Material.CRAFTING_TABLE)
                .name("&e" + display)
                .lore("&7Government price: &f" + GuiKit.fmt(price),
                        "&aClick for more options.")
                .build();
    }

    private ItemStack stockItem(String stockId) {
        ConfigurationSection stock = module.businessConfig().getSection("government-stock." + stockId);
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

    private static final class Draft {
        int count = 1;
    }
}
