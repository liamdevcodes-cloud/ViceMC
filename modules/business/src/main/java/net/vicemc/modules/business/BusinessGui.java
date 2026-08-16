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
        boolean owner = business.owner.equals(player.getUniqueId());
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
        if (owner) {
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.GOLD_INGOT, "&6Sell products",
                    "&7Sell factory products to",
                    "&7the government."), (p, c) -> openSellProduct(p, business.id, 1));
            builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.EMERALD, "&6Restock",
                    "&7Buy government wholesale",
                    "&7stock for this business."), (p, c) -> openStock(p, 1));
            if ("DEALERSHIP".equals(business.type)) {
                builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.SADDLE, "&aSell vehicle",
                        "&7Sell a vehicle from stock",
                        "&7to a customer."), (p, c) -> openDealerCustomer(p, business.id, 1));
            }
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
