package net.vicemc.modules.stock;

import com.google.gson.reflect.TypeToken;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.Json;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import net.vicemc.modules.business.Business;
import net.vicemc.modules.business.BusinessModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The heart of the exchange: company registry, the limit-order book, trade
 * matching with escrow, the 6-hour volume-weighted re-pricing window and
 * treasury operations (withdrawals and dividends). Every mutation is
 * persisted through the storage service.
 */
public final class StockManager {

    private static final TypeToken<List<StockCompany>> COMPANY_TYPE = new TypeToken<List<StockCompany>>() {
    };
    private static final TypeToken<List<Order>> ORDER_TYPE = new TypeToken<List<Order>>() {
    };
    private static final TypeToken<List<Trade>> TRADE_TYPE = new TypeToken<List<Trade>>() {
    };
    private static final TypeToken<Map<Integer, double[]>> WINDOW_TYPE = new TypeToken<Map<Integer, double[]>>() {
    };
    private static final int MAX_TRADES = 2000;

    private final ViceModuleContext ctx;
    private final YamlConfig config;
    private final BusinessModule business;
    private final List<StockCompany> companies = new ArrayList<>();
    private final List<Order> orders = new ArrayList<>();
    private List<Trade> trades = new ArrayList<>();
    private final Map<Integer, double[]> windowStats = new LinkedHashMap<>();
    private long windowStartedAt;

    public StockManager(ViceModuleContext ctx, YamlConfig config, BusinessModule business) {
        this.ctx = ctx;
        this.config = config;
        this.business = business;
        ctx.storage().getModuleData("stock", "companies").ifPresent(json -> {
            List<StockCompany> loaded = Json.fromJson(json, COMPANY_TYPE.getType());
            if (loaded != null) {
                companies.addAll(loaded);
            }
        });
        ctx.storage().getModuleData("stock", "orders").ifPresent(json -> {
            List<Order> loaded = Json.fromJson(json, ORDER_TYPE.getType());
            if (loaded != null) {
                orders.addAll(loaded);
            }
        });
        ctx.storage().getModuleData("stock", "trades").ifPresent(json -> {
            List<Trade> loaded = Json.fromJson(json, TRADE_TYPE.getType());
            if (loaded != null) {
                trades.addAll(loaded);
            }
        });
        windowStartedAt = ctx.storage().getModuleData("stock", "window:started")
                .map(Long::parseLong)
                .orElse(System.currentTimeMillis());
        ctx.storage().getModuleData("stock", "window:stats").ifPresent(json -> {
            Map<Integer, double[]> loaded = Json.fromJson(json, WINDOW_TYPE.getType());
            if (loaded != null) {
                windowStats.putAll(loaded);
            }
        });
    }

    public void save() {
        ctx.storage().setModuleData("stock", "companies", Json.toJson(companies));
        ctx.storage().setModuleData("stock", "orders", Json.toJson(orders));
        ctx.storage().setModuleData("stock", "trades", Json.toJson(trades));
        ctx.storage().setModuleData("stock", "window:started", String.valueOf(windowStartedAt));
        ctx.storage().setModuleData("stock", "window:stats", Json.toJson(windowStats));
    }

    // --- Listing / accessors ----------------------------------------------

    public boolean listCompany(Player player, int businessId, long shares) {
        Business target = businessModule().manager().byId(businessId).orElse(null);
        if (target == null) {
            player.sendMessage(Text.color("&cBusiness not found."));
            return false;
        }
        if (!target.owner.equals(player.getUniqueId())) {
            player.sendMessage(Text.color("&cOnly the business owner can list it."));
            return false;
        }
        if (companyOfBusiness(businessId).isPresent()) {
            player.sendMessage(Text.color("&cThat business is already listed."));
            return false;
        }
        long min = config.getInt("min-shares", 10000);
        long max = config.getInt("max-shares", 1000000);
        if (shares < min || shares > max) {
            player.sendMessage(Text.color("&cShare count must be between &f" + min + "&c and &f" + max + "&c."));
            return false;
        }
        double ipo = config.getDouble("ipo-price", 100);
        StockCompany company = new StockCompany();
        company.id = nextCompanyId();
        company.businessId = businessId;
        company.name = target.name;
        company.totalShares = shares;
        company.holdings.put(StockCompany.COMPANY, shares);
        company.price = ipo;
        company.windowStartPrice = ipo;
        company.changePct = 0;
        company.treasury = 0;
        company.createdAt = System.currentTimeMillis();
        companies.add(company);
        save();
        ctx.notifications().broadcast("&6" + company.name + "&a went public at &f" + Text.moneyPlain(ipo)
                + "&a per share (" + shares + " shares).");
        return true;
    }

    public List<StockCompany> companies() {
        return companies;
    }

    public StockCompany company(int id) {
        return companies.stream().filter(c -> c.id == id).findFirst().orElse(null);
    }

    public Optional<StockCompany> companyOfBusiness(int businessId) {
        return companies.stream().filter(c -> c.businessId == businessId).findFirst();
    }

    public Business businessOf(StockCompany company) {
        if (business == null || company == null) {
            return null;
        }
        return business.manager().byId(company.businessId).orElse(null);
    }

    public BusinessModule businessModule() {
        return business;
    }

    // --- Orders -----------------------------------------------------------

    public void placeBuyOrder(Player player, int companyId, long shares, double price) {
        if (company(companyId) == null) {
            player.sendMessage(Text.color("&cCompany not found."));
            return;
        }
        if (shares <= 0) {
            player.sendMessage(Text.color("&cShare count must be positive."));
            return;
        }
        if (price < config.getDouble("min-order-price", 1) || price > config.getDouble("max-order-price", 100000)) {
            player.sendMessage(Text.color("&cPrice must be between &f"
                    + Text.moneyPlain(config.getDouble("min-order-price", 1))
                    + "&c and &f" + Text.moneyPlain(config.getDouble("max-order-price", 100000)) + "&c."));
            return;
        }
        double cost = price * shares;
        var result = ctx.economy().withdraw(player.getUniqueId(), cost, "stock buy order");
        if (!result.success()) {
            player.sendMessage(Text.color("&cYou need &f" + Text.moneyPlain(cost) + "&c for this buy order."));
            return;
        }
        Order order = new Order();
        order.id = nextOrderId();
        order.companyId = companyId;
        order.buy = true;
        order.player = player.getUniqueId();
        order.seller = player.getUniqueId().toString();
        order.shares = shares;
        order.price = price;
        order.escrow = cost;
        order.createdAt = System.currentTimeMillis();
        orders.add(order);
        matchOrder(order);
        long filled = shares - order.shares;
        if (order.shares <= 0) {
            player.sendMessage(Text.color("&aYour buy order filled: &f" + shares + "&a share(s)."));
        } else if (filled > 0) {
            player.sendMessage(Text.color("&aBuy order partially filled: &f" + filled
                    + "&a share(s), &f" + order.shares + "&a resting at &f" + Text.moneyPlain(price) + "&a."));
        } else {
            player.sendMessage(Text.color("&aBuy order placed: &f" + order.shares
                    + "&a share(s) at &f" + Text.moneyPlain(price) + "&a."));
        }
    }

    public void placeSellOrder(Player player, int companyId, long shares, double price, boolean companySale) {
        StockCompany company = company(companyId);
        if (company == null) {
            player.sendMessage(Text.color("&cCompany not found."));
            return;
        }
        if (shares <= 0) {
            player.sendMessage(Text.color("&cShare count must be positive."));
            return;
        }
        if (price < config.getDouble("min-order-price", 1) || price > config.getDouble("max-order-price", 100000)) {
            player.sendMessage(Text.color("&cPrice must be between &f"
                    + Text.moneyPlain(config.getDouble("min-order-price", 1))
                    + "&c and &f" + Text.moneyPlain(config.getDouble("max-order-price", 100000)) + "&c."));
            return;
        }
        if (companySale) {
            Business biz = businessOf(company);
            if (biz == null || !biz.owner.equals(player.getUniqueId())) {
                player.sendMessage(Text.color("&cOnly the company owner can sell treasury shares."));
                return;
            }
            if (company.treasuryShares() < shares) {
                player.sendMessage(Text.color("&cThe treasury only holds &f" + company.treasuryShares() + "&c share(s)."));
                return;
            }
        } else if (company.heldBy(player.getUniqueId()) < shares) {
            player.sendMessage(Text.color("&cYou only hold &f" + company.heldBy(player.getUniqueId()) + "&c share(s)."));
            return;
        }
        Order order = new Order();
        order.id = nextOrderId();
        order.companyId = companyId;
        order.buy = false;
        order.player = player.getUniqueId();
        order.seller = companySale ? StockCompany.COMPANY : player.getUniqueId().toString();
        order.shares = shares;
        order.price = price;
        order.escrow = 0;
        order.createdAt = System.currentTimeMillis();
        orders.add(order);
        matchOrder(order);
        long filled = shares - order.shares;
        if (order.shares <= 0) {
            player.sendMessage(Text.color("&aYour sell order filled: &f" + shares + "&a share(s)."));
        } else if (filled > 0) {
            player.sendMessage(Text.color("&aSell order partially filled: &f" + filled
                    + "&a share(s), &f" + order.shares + "&a resting at &f" + Text.moneyPlain(price) + "&a."));
        } else {
            player.sendMessage(Text.color("&aSell order placed: &f" + order.shares
                    + "&a share(s) at &f" + Text.moneyPlain(price) + "&a."));
        }
    }

    public boolean cancelOrder(Player player, long orderId) {
        Order order = orders.stream().filter(o -> o.id == orderId).findFirst().orElse(null);
        if (order == null || !order.player.equals(player.getUniqueId())) {
            player.sendMessage(Text.color("&cOrder not found."));
            return false;
        }
        orders.remove(order);
        if (order.buy) {
            refundEscrow(order);
        }
        save();
        player.sendMessage(Text.color("&aCancelled order #" + orderId + "."));
        return true;
    }

    /** Transfers shares the giver owns to another player. */
    public boolean giveShares(Player giver, UUID recipient, int companyId, long shares) {
        StockCompany company = company(companyId);
        if (company == null) {
            giver.sendMessage(Text.color("&cCompany not found."));
            return false;
        }
        if (recipient == null || recipient.equals(giver.getUniqueId())) {
            giver.sendMessage(Text.color("&cYou cannot give shares to yourself."));
            return false;
        }
        if (shares <= 0) {
            giver.sendMessage(Text.color("&cShare count must be positive."));
            return false;
        }
        long held = company.heldBy(giver.getUniqueId());
        if (held < shares) {
            giver.sendMessage(Text.color("&cYou only hold &f" + held + "&c share(s) of &f" + company.name + "&c."));
            return false;
        }
        company.addHeld(giver.getUniqueId(), -shares);
        company.addHeld(recipient, shares);
        save();
        giver.sendMessage(Text.color("&aGave &f" + shares + "&a share(s) of &f" + company.name
                + "&a to &f" + nameOf(recipient) + "&a."));
        Player target = Bukkit.getPlayer(recipient);
        if (target != null) {
            ctx.notifications().msg(target, "&a" + giver.getName() + " gave you &f" + shares
                    + "&a share(s) of &f" + company.name + "&a.");
        }
        return true;
    }

    // --- Matching ---------------------------------------------------------

    /**
     * Runs the resting order closest to the market against the incoming order
     * until no more fills are possible. Execution happens at the resting
     * order's price, exactly like a real exchange.
     */
    private void matchOrder(Order incoming) {
        while (incoming.shares > 0) {
            StockCompany company = company(incoming.companyId);
            if (company == null) {
                orders.remove(incoming);
                refundEscrow(incoming);
                save();
                return;
            }
            Order opposite = bestMatch(incoming);
            if (opposite == null) {
                break;
            }
            Order sellerOrder = incoming.buy ? opposite : incoming;
            long available = availableFor(sellerOrder);
            if (available <= 0) {
                orders.remove(sellerOrder);
                if (sellerOrder == incoming) {
                    refundEscrow(incoming);
                    break;
                }
                continue;
            }
            if (sellerOrder == opposite) {
                opposite.shares = Math.min(opposite.shares, available);
                if (opposite.shares <= 0) {
                    orders.remove(opposite);
                    continue;
                }
            }
            long shares = Math.min(incoming.shares, opposite.shares);
            executeTrade(incoming, opposite, shares);
        }
        if (incoming.shares <= 0) {
            refundEscrow(incoming);
            orders.remove(incoming);
        }
        save();
    }

    private Order bestMatch(Order incoming) {
        Order best = null;
        for (Order o : orders) {
            if (o == incoming || o.companyId != incoming.companyId || o.buy == incoming.buy) {
                continue;
            }
            if (incoming.buy) {
                if (o.price > incoming.price) {
                    continue;
                }
                if (best == null || o.price < best.price
                        || (o.price == best.price && o.createdAt < best.createdAt)) {
                    best = o;
                }
            } else {
                if (o.price < incoming.price) {
                    continue;
                }
                if (best == null || o.price > best.price
                        || (o.price == best.price && o.createdAt < best.createdAt)) {
                    best = o;
                }
            }
        }
        return best;
    }

    /** How many shares a sell order can actually deliver right now. */
    private long availableFor(Order order) {
        if (!order.buy) {
            StockCompany company = company(order.companyId);
            if (company == null) {
                return 0;
            }
            return order.isCompanySale() ? company.treasuryShares() : company.heldBy(order.seller);
        }
        return order.shares;
    }

    private void executeTrade(Order incoming, Order opposite, long shares) {
        StockCompany company = company(incoming.companyId);
        if (company == null) {
            return;
        }
        Order buyerOrder = incoming.buy ? incoming : opposite;
        Order sellerOrder = incoming.buy ? opposite : incoming;
        double tradePrice = opposite.price;
        double cost = tradePrice * shares;
        if (buyerOrder.escrow >= cost) {
            buyerOrder.escrow -= cost;
        } else {
            buyerOrder.escrow = 0;
        }
        if (sellerOrder.isCompanySale()) {
            company.treasury += cost;
        } else {
            ctx.economy().deposit(sellerOrder.player, cost, "stock sale");
        }
        company.addHeld(buyerOrder.player, shares);
        company.addHeld(sellerOrder.seller, -shares);
        incoming.shares -= shares;
        opposite.shares -= shares;

        Trade trade = new Trade();
        trade.id = nextTradeId();
        trade.companyId = company.id;
        trade.buyer = buyerOrder.player;
        trade.seller = sellerOrder.seller;
        trade.shares = shares;
        trade.price = tradePrice;
        trade.createdAt = System.currentTimeMillis();
        trades.add(trade);
        trimTrades();

        double[] stats = windowStats.computeIfAbsent(company.id, k -> new double[2]);
        stats[0] += tradePrice * shares;
        stats[1] += shares;

        ctx.events().publish("stock", Map.of(
                "type", "trade",
                "company", company.id,
                "shares", shares,
                "price", tradePrice));
    }

    private void refundEscrow(Order buyOrder) {
        if (buyOrder.escrow > 0.01) {
            ctx.economy().deposit(buyOrder.player, buyOrder.escrow, "stock buy refund");
            buyOrder.escrow = 0;
        }
    }

    private void trimTrades() {
        if (trades.size() > MAX_TRADES) {
            trades = new ArrayList<>(trades.subList(trades.size() - MAX_TRADES, trades.size()));
        }
    }

    // --- Treasury ---------------------------------------------------------

    public boolean withdrawTreasury(Player ceo, int companyId, double amount) {
        StockCompany company = company(companyId);
        Business business = businessOf(company);
        if (business == null || !business.owner.equals(ceo.getUniqueId())) {
            ceo.sendMessage(Text.color("&cYou are not the owner of this company."));
            return false;
        }
        if (amount <= 0) {
            ceo.sendMessage(Text.color("&cInvalid amount."));
            return false;
        }
        if (amount > company.treasury) {
            ceo.sendMessage(Text.color("&cThe treasury only holds &f" + Text.moneyPlain(company.treasury) + "&c."));
            return false;
        }
        company.treasury -= amount;
        ctx.economy().deposit(ceo.getUniqueId(), amount, "treasury withdrawal");
        save();
        ceo.sendMessage(Text.color("&aWithdrew &f" + Text.moneyPlain(amount) + "&a from the treasury."));
        return true;
    }

    /** Pays a per-share dividend to every shareholder except the CEO. */
    public boolean payDividend(Player ceo, int companyId, double perShare) {
        StockCompany company = company(companyId);
        Business business = businessOf(company);
        if (business == null || !business.owner.equals(ceo.getUniqueId())) {
            ceo.sendMessage(Text.color("&cYou are not the owner of this company."));
            return false;
        }
        if (perShare <= 0) {
            ceo.sendMessage(Text.color("&cInvalid dividend per share."));
            return false;
        }
        long outside = company.outstanding(ceo.getUniqueId());
        if (outside <= 0) {
            ceo.sendMessage(Text.color("&cThere are no outside shareholders to pay."));
            return false;
        }
        double total = perShare * outside;
        if (total > company.treasury) {
            ceo.sendMessage(Text.color("&cThe treasury needs &f" + Text.moneyPlain(total)
                    + "&c for this dividend but only holds &f" + Text.moneyPlain(company.treasury) + "&c."));
            return false;
        }
        company.treasury -= total;
        int paid = 0;
        for (Map.Entry<String, Long> entry : new ArrayList<>(company.holdings.entrySet())) {
            String key = entry.getKey();
            if (StockCompany.COMPANY.equals(key) || entry.getValue() <= 0) {
                continue;
            }
            UUID holder;
            try {
                holder = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (holder.equals(ceo.getUniqueId())) {
                continue;
            }
            ctx.economy().deposit(holder, entry.getValue() * perShare, "stock dividend");
            paid++;
        }
        save();
        ceo.sendMessage(Text.color("&aPaid a &f" + Text.moneyPlain(perShare) + "&a per-share dividend to &f" + paid
                + "&a shareholder(s), total &f" + Text.moneyPlain(total) + "&a."));
        ctx.notifications().broadcast("&a" + company.name + " paid a dividend of &f"
                + Text.moneyPlain(perShare) + "&a per share.");
        return true;
    }

    // --- Re-pricing window ------------------------------------------------

    /**
     * Called every tick. When the configured window has elapsed, every company
     * is re-priced to the volume-weighted average of its trades in the window
     * and the change percentage is recorded.
     */
    public void checkWindow() {
        long windowSeconds = config.getInt("window-seconds", 21600);
        if (System.currentTimeMillis() - windowStartedAt < windowSeconds * 1000L) {
            return;
        }
        boolean changed = false;
        for (StockCompany company : companies) {
            double[] stats = windowStats.remove(company.id);
            if (stats != null && stats[1] > 0) {
                double vwap = stats[0] / stats[1];
                double base = company.windowStartPrice > 0 ? company.windowStartPrice : company.price;
                company.changePct = base > 0 ? (vwap - base) / base * 100 : 0;
                company.windowStartPrice = vwap;
                company.price = vwap;
                changed = true;
            } else {
                company.changePct = 0;
                company.windowStartPrice = company.price;
            }
        }
        windowStats.clear();
        windowStartedAt = System.currentTimeMillis();
        save();
        if (changed && !companies.isEmpty()) {
            long hours = Math.max(1, windowSeconds / 3600);
            ctx.notifications().broadcast("&6The stock market re-priced for the last " + hours
                    + " hours. Use &e/stock&6 to see prices.");
        }
    }

    // --- Queries for GUIs / chat ------------------------------------------

    public List<Order> ordersOfCompany(int companyId) {
        return orders.stream().filter(o -> o.companyId == companyId).toList();
    }

    /** Resting buy orders, best price first. */
    public List<Order> bidsOf(int companyId) {
        return orders.stream()
                .filter(o -> o.companyId == companyId && o.buy)
                .sorted((a, b) -> Double.compare(b.price, a.price) != 0
                        ? Double.compare(b.price, a.price)
                        : Long.compare(a.createdAt, b.createdAt))
                .toList();
    }

    /** Resting sell orders, best price first. */
    public List<Order> asksOf(int companyId) {
        return orders.stream()
                .filter(o -> o.companyId == companyId && !o.buy)
                .sorted((a, b) -> Double.compare(a.price, b.price) != 0
                        ? Double.compare(a.price, b.price)
                        : Long.compare(a.createdAt, b.createdAt))
                .toList();
    }

    public List<Order> ordersOfPlayer(UUID uuid) {
        return orders.stream().filter(o -> o.player.equals(uuid)).toList();
    }

    public List<Trade> tradesFor(int companyId) {
        int limit = config.getInt("trade-log-limit", 200);
        List<Trade> mine = trades.stream().filter(t -> t.companyId == companyId).toList();
        return mine.size() <= limit ? mine : new ArrayList<>(mine.subList(mine.size() - limit, mine.size()));
    }

    public List<Trade> recentTrades(int limit) {
        return trades.size() <= limit
                ? new ArrayList<>(trades)
                : new ArrayList<>(trades.subList(trades.size() - limit, trades.size()));
    }

    public long totalSharesOf(UUID uuid) {
        return companies.stream().mapToLong(c -> c.heldBy(uuid)).sum();
    }

    public double portfolioValue(UUID uuid) {
        return companies.stream().mapToDouble(c -> c.heldBy(uuid) * c.price).sum();
    }

    public Map<StockCompany, Long> holdingsOf(UUID uuid) {
        Map<StockCompany, Long> result = new LinkedHashMap<>();
        for (StockCompany company : companies) {
            long shares = company.heldBy(uuid);
            if (shares > 0) {
                result.put(company, shares);
            }
        }
        return result;
    }

    public String changeColor(StockCompany company) {
        if (company.changePct > 0.001) {
            return "&a+" + String.format("%.1f", company.changePct) + "%";
        }
        if (company.changePct < -0.001) {
            return "&c" + String.format("%.1f", company.changePct) + "%";
        }
        return "&70.0%";
    }

    public String nameOf(UUID uuid) {
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

    public String nameOfSeller(String seller) {
        if (StockCompany.COMPANY.equals(seller)) {
            return "company";
        }
        try {
            return nameOf(UUID.fromString(seller));
        } catch (IllegalArgumentException ex) {
            return "unknown";
        }
    }

    // --- Ids --------------------------------------------------------------

    private int nextCompanyId() {
        return companies.stream().mapToInt(c -> c.id).max().orElse(0) + 1;
    }

    private long nextOrderId() {
        return orders.stream().mapToLong(o -> o.id).max().orElse(0L) + 1;
    }

    private long nextTradeId() {
        return trades.stream().mapToLong(t -> t.id).max().orElse(0L) + 1;
    }
}
