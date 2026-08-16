package net.vicemc.modules.stock;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.modules.business.Business;
import net.vicemc.modules.business.BusinessModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The stock exchange menus: dashboard, market, company view, order book,
 * public ledger, portfolio, open orders, go public / IPO and the CEO panel.
 * Free-text inputs (share counts, prices, amounts) use chat prompts.
 */
public final class StockGui {

    private static final int PER_PAGE = 27;

    private final ViceModuleContext ctx;
    private final StockModule module;

    public StockGui(ViceModuleContext ctx, StockModule module) {
        this.ctx = ctx;
        this.module = module;
    }

    private StockManager manager() {
        return module.manager();
    }

    private StockPrompts prompts() {
        return module.prompts();
    }

    // --- Dashboard --------------------------------------------------------

    public void openDashboard(Player player) {
        var builder = ctx.gui().builder(GuiKit.title("Stock Exchange"), 6);
        GuiKit.frame(builder);
        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&6How it works",
                "&7Listed companies trade through a",
                "&7public limit-order book. Prices",
                "&7re-price every 6h from real trades."), GuiKit.NONE);
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.GOLD_BLOCK)
                .name("&6Vice Stock Exchange")
                .lore("&7Listed companies: &f" + manager().companies().size(),
                        "&7Your shares: &f" + manager().totalSharesOf(player.getUniqueId()),
                        "&7Your balance: &f" + GuiKit.fmt(ctx.economy().balance(player.getUniqueId())))
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.BOOK, "&6Market",
                "&7Browse all listed companies."), (p, c) -> openMarket(p, 1));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.GOLD_NUGGET, "&6Your portfolio",
                "&7Your shares and open orders."), (p, c) -> openPortfolio(p, 1));
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.PAPER, "&6Public ledger",
                "&7Recent trades across the market."), (p, c) -> openLedger(p, 1));
        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.EMERALD, "&6Go public",
                "&7List one of your businesses",
                "&7on the exchange."), (p, c) -> openGoPublic(p, 1));
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.CHEST, "&6Your company",
                "&7Manage your listed company."), (p, c) -> openMyCompany(p));
        builder.open(player);
    }

    // --- Market -----------------------------------------------------------

    public void openMarket(Player player, int page) {
        List<StockCompany> companies = manager().companies();
        int pages = GuiKit.Pages.pages(companies.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Market"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the stock exchange"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.BOOK, "&6Market",
                "&7" + companies.size() + " listed company(ies).",
                "&7Click one to trade."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<StockCompany> slice = GuiKit.Pages.slice(companies, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            StockCompany company = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.GOLD_INGOT)
                    .name("&e" + company.name)
                    .lore("&7Price: &f" + GuiKit.fmt(company.price),
                            "&7Change (6h): &f" + manager().changeColor(company),
                            "&7In circulation: &f" + company.floatShares(),
                            "&aClick to trade.")
                    .build(), (p, c) -> openCompany(p, company.id));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openMarket(p, next));
        builder.open(player);
    }

    // --- Company view -----------------------------------------------------

    public void openCompany(Player player, int companyId) {
        StockCompany company = manager().company(companyId);
        if (company == null) {
            ctx.notifications().warn(player, "That company no longer exists.");
            openMarket(player, 1);
            return;
        }
        Business business = manager().businessOf(company);
        boolean isCeo = business != null && business.owner.equals(player.getUniqueId());
        var builder = ctx.gui().builder(GuiKit.title("Stock: " + company.name), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the market"), (p, c) -> openMarket(p, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.GOLD_INGOT)
                .name("&e" + company.name)
                .lore("&7Price: &f" + GuiKit.fmt(company.price),
                        "&7Change (6h): &f" + manager().changeColor(company),
                        "&7In circulation: &f" + company.floatShares(),
                        "&7Your shares: &f" + company.heldBy(player.getUniqueId()))
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.LIME_DYE, "&aBuy shares",
                "&7Place a buy order."), (p, c) -> promptBuy(p, company));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.RED_DYE, "&cSell shares",
                "&7Place a sell order from",
                "&7your own holdings."), (p, c) -> promptSell(p, company));
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.BOOK, "&6Order book",
                "&7Live bids and asks."), (p, c) -> openOrderBook(p, companyId));
        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.PAPER, "&6Trades",
                "&7Public trade history."), (p, c) -> openTrades(p, companyId, 1));
        if (isCeo) {
            builder.item(GuiKit.ACTION_5, GuiKit.cta(Material.CHEST, "&6CEO panel",
                    "&7Treasury, dividends and",
                    "&7storefront sales."), (p, c) -> openCeo(p, companyId));
        } else {
            builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Your orders",
                    "&7See and cancel your",
                    "&7resting orders."), (p, c) -> openOrders(p, 1));
        }
        builder.open(player);
    }

    // --- Order book -------------------------------------------------------

    public void openOrderBook(Player player, int companyId) {
        StockCompany company = manager().company(companyId);
        if (company == null) {
            openMarket(player, 1);
            return;
        }
        List<Order> bids = manager().bidsOf(companyId);
        List<Order> asks = manager().asksOf(companyId);
        double bestBid = bids.isEmpty() ? 0 : bids.get(0).price;
        double bestAsk = asks.isEmpty() ? 0 : asks.get(0).price;
        var builder = ctx.gui().builder(GuiKit.title("Order book: " + company.name), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("company view"), (p, c) -> openCompany(p, companyId));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.BOOK)
                .name("&e" + company.name)
                .lore("&7Best bid: &f" + (bestBid > 0 ? GuiKit.fmt(bestBid) : "&7-"),
                        "&7Best ask: &f" + (bestAsk > 0 ? GuiKit.fmt(bestAsk) : "&7-"),
                        "&7Spread: &f" + (bestBid > 0 && bestAsk > 0
                                ? GuiKit.fmt(Math.max(0, bestAsk - bestBid)) : "&7-"),
                        "&7Click your own order to cancel.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        int slot = GuiKit.GRID_FIRST;
        for (int i = 0; i < bids.size() && slot < 32; i++) {
            Order order = bids.get(i);
            boolean mine = order.player.equals(player.getUniqueId());
            builder.item(slot++, ItemBuilder.of(Material.LIME_WOOL)
                    .name("&aBUY &f" + order.shares + " @ &f" + GuiKit.fmt(order.price))
                    .lore("&7By: &f" + manager().nameOf(order.player),
                            mine ? "&eClick to cancel." : "")
                    .build(), (p, c) -> {
                if (mine) {
                    manager().cancelOrder(p, order.id);
                    openOrderBook(p, companyId);
                }
            });
        }
        for (int i = 0; i < asks.size() && slot < 45; i++) {
            Order order = asks.get(i);
            boolean mine = order.player.equals(player.getUniqueId());
            builder.item(slot++, ItemBuilder.of(Material.RED_WOOL)
                    .name("&cSELL &f" + order.shares + " @ &f" + GuiKit.fmt(order.price))
                    .lore(order.isCompanySale()
                                    ? "&7By: &fcompany"
                                    : "&7By: &f" + manager().nameOf(order.player),
                            mine ? "&eClick to cancel." : "")
                    .build(), (p, c) -> {
                if (mine) {
                    manager().cancelOrder(p, order.id);
                    openOrderBook(p, companyId);
                }
            });
        }
        builder.open(player);
    }

    // --- Trades / ledger --------------------------------------------------

    public void openTrades(Player player, int companyId, int page) {
        StockCompany company = manager().company(companyId);
        if (company == null) {
            openMarket(player, 1);
            return;
        }
        List<Trade> all = manager().tradesFor(companyId);
        List<Trade> reversed = new ArrayList<>(all);
        Collections.reverse(reversed);
        int pages = GuiKit.Pages.pages(reversed.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Trades: " + company.name), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("company view"), (p, c) -> openCompany(p, companyId));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.PAPER, "&6Trades",
                "&7" + all.size() + " trade(s).",
                "&7Public record of every trade."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Trade> slice = GuiKit.Pages.slice(reversed, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Trade trade = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.MAP)
                    .name("&b" + trade.shares + " shares @ &f" + GuiKit.fmt(trade.price))
                    .lore("&7" + manager().nameOf(trade.buyer) + " bought from &f"
                                    + manager().nameOfSeller(trade.seller),
                            "&7" + timeAgo(trade.createdAt))
                    .build(), GuiKit.NONE);
        }
        addPaging(builder, player, safe, pages, (p, next) -> openTrades(p, companyId, next));
        builder.open(player);
    }

    public void openLedger(Player player, int page) {
        List<Trade> all = manager().recentTrades(200);
        List<Trade> reversed = new ArrayList<>(all);
        Collections.reverse(reversed);
        int pages = GuiKit.Pages.pages(reversed.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Public ledger"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the stock exchange"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.PAPER, "&6Public ledger",
                "&7Every trade across the market.",
                "&7Click one to open the company."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Trade> slice = GuiKit.Pages.slice(reversed, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Trade trade = slice.get(i);
            StockCompany company = manager().company(trade.companyId);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.MAP)
                    .name("&b" + trade.shares + " @ &f" + GuiKit.fmt(trade.price))
                    .lore("&7" + manager().nameOf(trade.buyer) + " bought from &f"
                                    + manager().nameOfSeller(trade.seller),
                            company != null ? "&7Company: &f" + company.name : "",
                            "&7" + timeAgo(trade.createdAt),
                            "&eClick to open the company.")
                    .build(), (p, c) -> {
                if (company != null) {
                    openCompany(p, company.id);
                }
            });
        }
        addPaging(builder, player, safe, pages, (p, next) -> openLedger(p, next));
        builder.open(player);
    }

    // --- Portfolio / orders ----------------------------------------------

    public void openPortfolio(Player player, int page) {
        Map<StockCompany, Long> holdings = manager().holdingsOf(player.getUniqueId());
        List<StockCompany> companies = new ArrayList<>(holdings.keySet());
        int pages = GuiKit.Pages.pages(companies.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Your portfolio"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the stock exchange"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.GOLD_NUGGET)
                .name("&6Your portfolio")
                .lore("&7Companies held: &f" + companies.size(),
                        "&7Total shares: &f" + manager().totalSharesOf(player.getUniqueId()),
                        "&7Market value: &f" + GuiKit.fmt(manager().portfolioValue(player.getUniqueId())))
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.BOOK, "&6Your open orders",
                "&7See and cancel your resting",
                "&7buy and sell orders."), (p, c) -> openOrders(p, 1));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.NAME_TAG, "&6Give shares",
                "&7Gift shares you own to",
                "&7another player."), (p, c) -> openGivePicker(p, 1));
        List<StockCompany> slice = GuiKit.Pages.slice(companies, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            StockCompany company = slice.get(i);
            long shares = holdings.get(company);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.GOLD_INGOT)
                    .name("&e" + company.name)
                    .lore("&7Shares: &f" + shares,
                            "&7Value: &f" + GuiKit.fmt(shares * company.price),
                            "&aClick to trade.")
                    .build(), (p, c) -> openCompany(p, company.id));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openPortfolio(p, next));
        builder.open(player);
    }

    public void openOrders(Player player, int page) {
        List<Order> orders = manager().ordersOfPlayer(player.getUniqueId());
        int pages = GuiKit.Pages.pages(orders.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Your orders"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("your portfolio"), (p, c) -> openPortfolio(p, 1));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.BOOK, "&6Open orders",
                "&7" + orders.size() + " resting order(s).",
                "&7Click one to cancel it."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Order> slice = GuiKit.Pages.slice(orders, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Order order = slice.get(i);
            StockCompany company = manager().company(order.companyId);
            String companyName = company != null ? company.name : "#" + order.companyId;
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(order.buy ? Material.LIME_WOOL : Material.RED_WOOL)
                    .name((order.buy ? "&aBUY" : "&cSELL") + " &f" + order.shares
                            + " @ &f" + GuiKit.fmt(order.price))
                    .lore("&7Company: &f" + companyName,
                            "&7" + timeAgo(order.createdAt),
                            "&eClick to cancel.")
                    .build(), (p, c) -> {
                manager().cancelOrder(p, order.id);
                openOrders(p, safe);
            });
        }
        addPaging(builder, player, safe, pages, (p, next) -> openOrders(p, next));
        builder.open(player);
    }

    // --- Give shares ------------------------------------------------------

    public void openGivePicker(Player player, int page) {
        Map<StockCompany, Long> holdings = manager().holdingsOf(player.getUniqueId());
        List<StockCompany> companies = new ArrayList<>(holdings.keySet());
        int pages = GuiKit.Pages.pages(companies.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Give shares"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("your portfolio"), (p, c) -> openPortfolio(p, 1));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.NAME_TAG, "&6Give shares",
                "&7Click a company to give",
                "&7shares you own to a player."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<StockCompany> slice = GuiKit.Pages.slice(companies, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            StockCompany company = slice.get(i);
            long shares = holdings.get(company);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.GOLD_INGOT)
                    .name("&e" + company.name)
                    .lore("&7Shares held: &f" + shares,
                            "&aClick to give some away.")
                    .build(), (p, c) -> promptGiveTarget(p, company));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openGivePicker(p, next));
        builder.open(player);
    }

    private void promptGiveTarget(Player player, StockCompany company) {
        ask(player, "&6Give &f" + company.name + "&6 shares. &6Which player? &eType their name, or 'cancel'.",
                (p, input) -> giveTarget(p, company, input));
    }

    private void giveTarget(Player player, StockCompany company, String input) {
        if (isCancel(input)) {
            openGivePicker(player, 1);
            return;
        }
        Player target = Bukkit.getPlayer(input);
        if (target == null) {
            ask(player, "&cNo online player named '&f" + input + "&c'. &6Try again, or type 'cancel'.",
                    (p, s) -> giveTarget(p, company, s));
            return;
        }
        long held = company.heldBy(player.getUniqueId());
        ask(player, "&6You hold &f" + held + "&6 shares. How many to give to &f" + target.getName()
                        + "&6? &eType a number, or 'cancel'.",
                (p, s) -> giveShares(p, company, target, s));
    }

    private void giveShares(Player player, StockCompany company, Player target, String input) {
        if (isCancel(input)) {
            openGivePicker(player, 1);
            return;
        }
        Long shares = parseLong(input);
        if (shares == null || shares <= 0) {
            ask(player, "&cThat isn't a valid number. &6How many shares to give? &eType 'cancel' to abort.",
                    (p, s) -> giveShares(p, company, target, s));
            return;
        }
        manager().giveShares(player, target.getUniqueId(), company.id, shares);
        openPortfolio(player, 1);
    }

    // --- Go public / IPO --------------------------------------------------

    public void openGoPublic(Player player, int page) {
        BusinessModule business = module.businessModule();
        List<Business> owned = business == null ? List.of() : business.manager().byOwner(player.getUniqueId());
        List<Business> unlisted = owned.stream()
                .filter(b -> manager().companyOfBusiness(b.id).isEmpty())
                .toList();
        int pages = GuiKit.Pages.pages(unlisted.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Go public"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the stock exchange"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.EMERALD, "&6Go public",
                "&7Your businesses not yet listed.",
                "&7Click one to set its IPO."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        if (unlisted.isEmpty()) {
            builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.BARRIER, "&7No businesses to list",
                    "&7Create a business with",
                    "&e/business create&7 first."), GuiKit.NONE);
        }
        List<Business> slice = GuiKit.Pages.slice(unlisted, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Business businessItem = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.CHEST)
                    .name("&e" + businessItem.name)
                    .lore("&7Type: &f" + businessType(businessItem),
                            "&7ID: &f#" + businessItem.id,
                            "&aClick to set the IPO.")
                    .build(), (p, c) -> openIpo(p, businessItem.id, module.minShares()));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openGoPublic(p, next));
        builder.open(player);
    }

    public void openIpo(Player player, int businessId, long shares) {
        Business business = module.businessModule().manager().byId(businessId).orElse(null);
        if (business == null) {
            openGoPublic(player, 1);
            return;
        }
        long min = module.minShares();
        long max = module.maxShares();
        long safe = Math.max(min, Math.min(max, shares));
        double ipo = module.ipoPrice();
        var builder = ctx.gui().builder(GuiKit.title("IPO: " + business.name), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("go public"), (p, c) -> openGoPublic(p, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.EMERALD)
                .name("&e" + business.name)
                .lore("&7IPO price: &f" + GuiKit.fmt(ipo) + " per share",
                        "&7Share range: &f" + min + " - " + max,
                        "&7Value at IPO: &f" + GuiKit.fmt(safe * ipo))
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, ItemBuilder.of(Material.GOLD_INGOT)
                .name("&6Shares: &f" + safe)
                .lore("&7Left: &f+1,000", "&7Right: &f-1,000",
                        "&7Shift-left: &f+10,000", "&7Shift-right: &f-10,000")
                .build(), (p, c) -> {
            long step = c.isShiftClick() ? 10_000 : 1_000;
            long delta = (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) ? -step : step;
            openIpo(p, businessId, Math.max(min, Math.min(max, safe + delta)));
        });
        builder.item(GuiKit.ACTION_3, GuiKit.cta(Material.GREEN_WOOL, "&aList &f" + safe + " shares",
                "&7Shares sell through the order book;",
                "&7proceeds fund the treasury."), (p, c) -> {
            manager().listCompany(p, businessId, safe);
            openDashboard(p);
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to go public."), (p, c) -> openGoPublic(p, 1));
        builder.open(player);
    }

    // --- CEO panel --------------------------------------------------------

    public void openCeo(Player player, int companyId) {
        StockCompany company = manager().company(companyId);
        if (company == null) {
            openCompany(player, companyId);
            return;
        }
        Business business = manager().businessOf(company);
        UUID ceo = business != null ? business.owner : null;
        double marketCap = ceo != null ? company.outstanding(ceo) * company.price : 0;
        var builder = ctx.gui().builder(GuiKit.title("CEO: " + company.name), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("company view"), (p, c) -> openCompany(p, companyId));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.CHEST)
                .name("&e" + company.name)
                .lore("&7Treasury: &f" + GuiKit.fmt(company.treasury),
                        "&7Treasury shares: &f" + company.treasuryShares(),
                        "&7Market cap: &f" + GuiKit.fmt(marketCap),
                        "&7Price: &f" + GuiKit.fmt(company.price))
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.EMERALD, "&6Sell treasury shares",
                "&7List company shares for sale;",
                "&7proceeds go to the treasury."), (p, c) -> promptTreasurySell(p, company));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.GOLD_INGOT, "&6Withdraw treasury",
                "&7Pay yourself from the treasury."), (p, c) -> promptWithdraw(p, company));
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.NETHER_STAR, "&6Pay a dividend",
                "&7Distribute a per-share payout",
                "&7to your shareholders."), (p, c) -> promptDividend(p, company));
        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.RED_DYE, "&cSell your shares",
                "&7Sell from your own holdings."), (p, c) -> promptSell(p, company));
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Back",
                "&7Return to the company view."), (p, c) -> openCompany(p, companyId));
        builder.open(player);
    }

    private void openMyCompany(Player player) {
        BusinessModule business = module.businessModule();
        List<Business> owned = business == null ? List.of() : business.manager().byOwner(player.getUniqueId());
        for (Business b : owned) {
            StockCompany company = manager().companyOfBusiness(b.id).orElse(null);
            if (company != null) {
                openCompany(player, company.id);
                return;
            }
        }
        ctx.notifications().warn(player, "You don't own a listed company yet. Use /stock then 'Go public'.");
        openDashboard(player);
    }

    // --- Prompts ----------------------------------------------------------

    private void promptBuy(Player player, StockCompany company) {
        ask(player, "&6How many shares of &f" + company.name + "&6 do you want to buy? &eType a number, or 'cancel'.",
                (p, input) -> buyShares(p, company, input));
    }

    private void buyShares(Player player, StockCompany company, String input) {
        if (isCancel(input)) {
            openCompany(player, company.id);
            return;
        }
        Long shares = parseLong(input);
        if (shares == null || shares <= 0) {
            ask(player, "&cThat isn't a valid number. &6How many shares of &f" + company.name
                            + "&6? &eType 'cancel' to abort.",
                    (p, s) -> buyShares(p, company, s));
            return;
        }
        ask(player, "&6At what price per share? &e(1 - " + (int) module.maxOrderPrice() + ")",
                (p, price) -> buyPrice(p, company, shares, price));
    }

    private void buyPrice(Player player, StockCompany company, long shares, String input) {
        if (isCancel(input)) {
            openCompany(player, company.id);
            return;
        }
        Double price = parseDouble(input);
        if (price == null || price <= 0) {
            ask(player, "&cThat isn't a valid price. &6Price per share? &eType 'cancel' to abort.",
                    (p, pr) -> buyPrice(p, company, shares, pr));
            return;
        }
        manager().placeBuyOrder(player, company.id, shares, price);
        openCompany(player, company.id);
    }

    private void promptSell(Player player, StockCompany company) {
        long held = company.heldBy(player.getUniqueId());
        if (held <= 0) {
            ctx.notifications().warn(player, "You hold no shares in " + company.name + ".");
            openCompany(player, company.id);
            return;
        }
        ask(player, "&6You hold &f" + held + "&6 shares. How many do you want to sell? &eType a number, or 'cancel'.",
                (p, input) -> sellShares(p, company, input));
    }

    private void sellShares(Player player, StockCompany company, String input) {
        if (isCancel(input)) {
            openCompany(player, company.id);
            return;
        }
        Long shares = parseLong(input);
        if (shares == null || shares <= 0) {
            ask(player, "&cThat isn't a valid number. &6How many shares of &f" + company.name
                            + "&6? &eType 'cancel' to abort.",
                    (p, s) -> sellShares(p, company, s));
            return;
        }
        ask(player, "&6At what price per share? &e(1 - " + (int) module.maxOrderPrice() + ")",
                (p, price) -> sellPrice(p, company, shares, price, false));
    }

    private void sellPrice(Player player, StockCompany company, long shares, String input, boolean companySale) {
        if (isCancel(input)) {
            openCompany(player, company.id);
            return;
        }
        Double price = parseDouble(input);
        if (price == null || price <= 0) {
            ask(player, "&cThat isn't a valid price. &6Price per share? &eType 'cancel' to abort.",
                    (p, pr) -> sellPrice(p, company, shares, pr, companySale));
            return;
        }
        manager().placeSellOrder(player, company.id, shares, price, companySale);
        if (companySale) {
            openCeo(player, company.id);
        } else {
            openCompany(player, company.id);
        }
    }

    private void promptTreasurySell(Player player, StockCompany company) {
        long treasury = company.treasuryShares();
        if (treasury <= 0) {
            ctx.notifications().warn(player, "The treasury holds no shares.");
            openCeo(player, company.id);
            return;
        }
        ask(player, "&6The treasury holds &f" + treasury + "&6 shares. How many to sell? &eType a number, or 'cancel'.",
                (p, input) -> treasuryShares(p, company, input));
    }

    private void treasuryShares(Player player, StockCompany company, String input) {
        if (isCancel(input)) {
            openCeo(player, company.id);
            return;
        }
        Long shares = parseLong(input);
        if (shares == null || shares <= 0) {
            ask(player, "&cThat isn't a valid number. &6How many treasury shares to sell? &eType 'cancel' to abort.",
                    (p, s) -> treasuryShares(p, company, s));
            return;
        }
        ask(player, "&6At what price per share? &e(1 - " + (int) module.maxOrderPrice() + ")",
                (p, price) -> sellPrice(p, company, shares, price, true));
    }

    private void promptWithdraw(Player player, StockCompany company) {
        ask(player, "&6Treasury holds &f" + GuiKit.fmt(company.treasury)
                        + "&6. How much do you want to withdraw? &eType a number, or 'cancel'.",
                (p, input) -> withdrawAmount(p, company, input));
    }

    private void withdrawAmount(Player player, StockCompany company, String input) {
        if (isCancel(input)) {
            openCeo(player, company.id);
            return;
        }
        Double amount = parseDouble(input);
        if (amount == null || amount <= 0) {
            ask(player, "&cThat isn't a valid amount. &6How much to withdraw? &eType 'cancel' to abort.",
                    (p, a) -> withdrawAmount(p, company, a));
            return;
        }
        manager().withdrawTreasury(player, company.id, amount);
        openCeo(player, company.id);
    }

    private void promptDividend(Player player, StockCompany company) {
        ask(player, "&6Pay a dividend to your shareholders. &6Per-share amount? &eType a number, or 'cancel'.",
                (p, input) -> dividendPerShare(p, company, input));
    }

    private void dividendPerShare(Player player, StockCompany company, String input) {
        if (isCancel(input)) {
            openCeo(player, company.id);
            return;
        }
        Double perShare = parseDouble(input);
        if (perShare == null || perShare <= 0) {
            ask(player, "&cThat isn't a valid amount. &6Per-share dividend? &eType 'cancel' to abort.",
                    (p, a) -> dividendPerShare(p, company, a));
            return;
        }
        manager().payDividend(player, company.id, perShare);
        openCeo(player, company.id);
    }

    // --- Helpers ----------------------------------------------------------

    private void ask(Player player, String message, BiConsumer<Player, String> callback) {
        prompts().prompt(player, message, callback);
    }

    private boolean isCancel(String input) {
        return input == null || input.equalsIgnoreCase("cancel")
                || input.equalsIgnoreCase("c") || input.equalsIgnoreCase("abort");
    }

    private Long parseLong(String input) {
        try {
            return Long.parseLong(input.trim().replace(",", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Double parseDouble(String input) {
        try {
            return Double.parseDouble(input.trim().replace(",", "").replace("$", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String timeAgo(long then) {
        long diff = Math.max(0, System.currentTimeMillis() - then) / 1000L;
        if (diff < 60) {
            return diff + "s ago";
        }
        if (diff < 3600) {
            return (diff / 60) + "m ago";
        }
        if (diff < 86400) {
            return (diff / 3600) + "h ago";
        }
        return (diff / 86400) + "d ago";
    }

    private String businessType(Business business) {
        return business.typeEnum() == null ? business.type : business.typeEnum().display();
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
}
