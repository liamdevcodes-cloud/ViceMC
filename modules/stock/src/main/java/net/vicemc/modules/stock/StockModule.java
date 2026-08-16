package net.vicemc.modules.stock;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.YamlConfig;
import net.vicemc.core.ViceCore;
import net.vicemc.modules.business.Business;
import net.vicemc.modules.business.BusinessModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.List;
import java.util.UUID;

/**
 * The player stock exchange: companies go public, shares trade through a
 * public limit-order book, and prices re-price every 6 hours from real
 * trades. The CEO runs the treasury, storefront sales and dividends.
 */
public final class StockModule implements ViceModule {

    private ViceModuleContext ctx;
    private YamlConfig config;
    private BusinessModule business;
    private StockManager manager;
    private StockPrompts prompts;
    private StockGui gui;
    private ScheduledTask task;

    @Override
    public String id() {
        return "stock";
    }

    @Override
    public String displayName() {
        return "Vice Stock Exchange";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("stock.yml");
        this.business = (BusinessModule) ViceCore.get().getModuleRegistry().module("business").orElse(null);
        if (business == null) {
            ctx.logger().warning("Business module not found; the stock exchange will not function.");
        }
        this.manager = new StockManager(ctx, config, business);
        this.prompts = new StockPrompts(this);
        this.gui = new StockGui(ctx, this);

        Bukkit.getPluginManager().registerEvents(prompts, ctx.plugin());
        startTicker();

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("stock")
                .aliases("market")
                .description("The player stock exchange")
                .executes(this::stock)
                .tabulates((c, a) -> {
                    if (a.size() <= 1) {
                        return List.of("list", "info", "buy", "sell", "cancel", "ledger", "give");
                    }
                    if (a.size() == 2 && (a.get(0).equals("info") || a.get(0).equals("buy")
                            || a.get(0).equals("sell") || a.get(0).equals("ledger")
                            || a.get(0).equals("give"))) {
                        return companyIds();
                    }
                    if (a.size() == 3 && a.get(0).equals("give")) {
                        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                    }
                    return List.of();
                })
                .build());

        ctx.logger().info("Stock exchange module ready.");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            cancelQuietly(task);
            task = null;
        }
        if (manager != null) {
            manager.save();
        }
    }

    private void startTicker() {
        long seconds = Math.max(1, config.getInt("tick-seconds", 60));
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(),
                scheduled -> manager.checkWindow(), 20L, seconds * 20L);
    }

    // --- GUI facade -------------------------------------------------------

    public ViceModuleContext context() {
        return ctx;
    }

    public StockManager manager() {
        return manager;
    }

    public StockPrompts prompts() {
        return prompts;
    }

    public BusinessModule businessModule() {
        return business;
    }

    public double ipoPrice() {
        return config.getDouble("ipo-price", 100);
    }

    public long minShares() {
        return config.getInt("min-shares", 10000);
    }

    public long maxShares() {
        return config.getInt("max-shares", 1000000);
    }

    public double minOrderPrice() {
        return config.getDouble("min-order-price", 1);
    }

    public double maxOrderPrice() {
        return config.getDouble("max-order-price", 100000);
    }

    public int windowSeconds() {
        return config.getInt("window-seconds", 21600);
    }

    // --- /stock -----------------------------------------------------------

    private void stock(CommandContext c) {
        if (!c.isPlayer()) {
            c.msg("&6/stock - the player stock exchange. Open with /stock from in-game.");
            return;
        }
        switch (c.arg(0)) {
            case "list" -> chatList(c);
            case "info" -> chatInfo(c);
            case "buy" -> chatBuy(c);
            case "sell" -> chatSell(c);
            case "cancel" -> chatCancel(c);
            case "ledger" -> chatLedger(c);
            case "give" -> chatGive(c);
            default -> gui.openDashboard(c.player());
        }
    }

    private void chatList(CommandContext c) {
        List<StockCompany> companies = manager.companies();
        if (companies.isEmpty()) {
            c.msg("&7No companies are listed yet. Owners can list one with /stock then 'Go public'.");
            return;
        }
        c.msg("&6Listed companies:");
        for (StockCompany company : companies) {
            c.msg("  &8#" + company.id + " &f" + company.name + " &7@ &f" + GuiKit.fmt(company.price)
                    + " &7(" + manager.changeColor(company) + "&7)");
        }
    }

    private void chatInfo(CommandContext c) {
        if (c.size() < 2) {
            c.usage("/stock info <id>");
            return;
        }
        StockCompany company = manager.company(c.argInt(1, -1));
        if (company == null) {
            c.error("Company not found.");
            return;
        }
        Business business = manager.businessOf(company);
        c.msg("&6#" + company.id + " &f" + company.name);
        c.msg("  &7Price: &f" + GuiKit.fmt(company.price) + "  &7Change (6h): &f" + manager.changeColor(company));
        c.msg("  &7In circulation: &f" + company.floatShares()
                + "  &7Treasury shares: &f" + company.treasuryShares());
        c.msg("  &7Treasury: &f" + GuiKit.fmt(company.treasury));
        if (business != null) {
            c.msg("  &7Business: &f#" + business.id + "  &7Owner: &f" + manager.nameOf(business.owner));
        }
    }

    private void chatBuy(CommandContext c) {
        if (c.size() < 4) {
            c.usage("/stock buy <id> <shares> <price>");
            return;
        }
        manager.placeBuyOrder(c.player(), c.argInt(1, -1), c.argInt(2, 0), c.argDouble(3, -1));
    }

    private void chatSell(CommandContext c) {
        if (c.size() < 4) {
            c.usage("/stock sell <id> <shares> <price>");
            return;
        }
        manager.placeSellOrder(c.player(), c.argInt(1, -1), c.argInt(2, 0), c.argDouble(3, -1), false);
    }

    private void chatCancel(CommandContext c) {
        if (c.size() < 2) {
            c.usage("/stock cancel <orderId>");
            return;
        }
        manager.cancelOrder(c.player(), c.argInt(1, -1));
    }

    private void chatLedger(CommandContext c) {
        if (c.size() < 2) {
            c.usage("/stock ledger <id>");
            return;
        }
        List<Trade> trades = manager.tradesFor(c.argInt(1, -1));
        if (trades.isEmpty()) {
            c.msg("&7No trades yet for that company.");
            return;
        }
        c.msg("&6Recent trades:");
        int show = Math.min(trades.size(), 8);
        for (int i = trades.size() - show; i < trades.size(); i++) {
            Trade trade = trades.get(i);
            c.msg("  &b" + trade.shares + " &7shares @ &f" + GuiKit.fmt(trade.price)
                    + " &7(" + manager.nameOf(trade.buyer) + " bought from &f" + manager.nameOfSeller(trade.seller) + "&7)");
        }
    }

    private void chatGive(CommandContext c) {
        if (c.size() < 4) {
            c.usage("/stock give <id> <player> <shares>");
            return;
        }
        UUID target = c.uuidArg(2);
        if (target == null) {
            c.error("Player not found or offline.");
            return;
        }
        manager.giveShares(c.player(), target, c.argInt(1, -1), c.argInt(3, 0));
    }

    private List<String> companyIds() {
        return manager.companies().stream().map(company -> String.valueOf(company.id)).toList();
    }

    private static void cancelQuietly(ScheduledTask task) {
        try {
            task.cancel();
        } catch (IllegalStateException ignored) {
        }
    }
}
