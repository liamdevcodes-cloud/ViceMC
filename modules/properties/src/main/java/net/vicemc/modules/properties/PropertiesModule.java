package net.vicemc.modules.properties;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import net.vicemc.core.ViceCore;
import net.vicemc.modules.business.BusinessModule;
import net.vicemc.modules.business.BusinessType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real estate: admin-created plots with postcode serials (001AA, 002AA ...),
 * per-type ownership limits (raised by a real estate permit from university),
 * rentals, member management, furniture you may only break if you placed it,
 * and interior-included resales that show the buyer exactly what is inside.
 */
public final class PropertiesModule implements ViceModule {

    private ViceModuleContext ctx;
    private YamlConfig config;
    private PlotManager manager;
    private PropertiesGui gui;
    private PromptManager prompts;
    private PlotListener listener;
    private PlotPresenceListener presence;
    private BusinessModule business;
    private ScheduledTask tickTask;
    private final Set<String> borderEnabled = ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return "properties";
    }

    @Override
    public String displayName() {
        return "Vice Properties";
    }

    @Override
    public String version() {
        return "2.2.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("properties.yml");
        this.manager = new PlotManager(ctx, config);
        this.gui = new PropertiesGui(ctx, this);
        this.prompts = new PromptManager(this);
        this.listener = new PlotListener(this);
        this.presence = new PlotPresenceListener(this);
        this.business = (BusinessModule) ViceCore.get().getModuleRegistry().module("business").orElse(null);
        if (business == null) {
            ctx.logger().warning("Business module not found; licensed business plots will not be gated.");
        }

        context.buildProtection().claim(block -> manager.at(block) != null);

        Bukkit.getPluginManager().registerEvents(listener, ctx.plugin());
        Bukkit.getPluginManager().registerEvents(prompts, ctx.plugin());
        Bukkit.getPluginManager().registerEvents(presence, ctx.plugin());

        long seconds = Math.max(20, config.getInt("tick-seconds", 60));
        tickTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(),
                task -> manager.expireTenants(config.getBoolean("drop-items-on-sell", true)),
                20L, seconds * 20L);

        registerCommands();
        ctx.logger().info("Properties module ready.");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    // --- Commands ---------------------------------------------------------

    private void registerCommands() {
        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("plot")
                .aliases("properties")
                .description("Your plots, the market and your real estate")
                .executes(c -> {
                    if (c.isPlayer()) {
                        gui.openDashboard(c.player());
                    } else {
                        c.msg("&6/plot opens the real estate menu in-game.");
                    }
                })
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("plotadmin")
                .aliases("pa")
                .permission("vicemc.properties.admin")
                .description("Administer plots, the wand, values and permits")
                .executes(this::plotadmin)
                .tabulates((c, a) -> {
                    if (a.size() <= 1) {
                        return List.of("wand", "pwand", "type", "value", "info", "remove", "give", "permit", "unlist", "rename", "admin");
                    }
                    if (a.get(0).equals("type") && a.size() == 2) {
                        return List.of("house", "apartment", "farm", "mine", "shop", "food_company",
                                "factory", "dealership", "jewelry_store", "bank");
                    }
                    if ((a.get(0).equals("info") || a.get(0).equals("remove")
                            || a.get(0).equals("give") || a.get(0).equals("unlist")
                            || a.get(0).equals("rename")) && a.size() == 2) {
                        return serials();
                    }
                    if ((a.get(0).equals("give") || a.get(0).equals("permit")) && a.size() == 3) {
                        return playerNames();
                    }
                    if (a.get(0).equals("permit") && a.size() == 4) {
                        return List.of("on", "off");
                    }
                    return List.of();
                })
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("plotinfo")
                .aliases("pi")
                .description("Details of the plot you are in, or a serial")
                .executes(this::plotinfo)
                .tabulates((c, a) -> a.size() == 1 ? serials() : List.of())
                .build());
    }

    private void plotinfo(CommandContext c) {
        if (!c.isPlayer()) {
            c.msg("&6/plotinfo shows the plot you are standing in.");
            return;
        }
        Player player = c.player();
        Plot plot;
        String serial = c.arg(0);
        if (!serial.isEmpty()) {
            plot = manager.bySerial(serial);
            if (plot == null) {
                c.error("Plot '" + serial + "' not found.");
                return;
            }
        } else {
            plot = manager.at(player.getLocation().getBlock());
            if (plot == null) {
                c.error("You are not standing in a plot. Usage: /plotinfo [serial]");
                return;
            }
        }
        c.msg("&6" + plot.serial + (plot.name.isEmpty() ? "" : " &7- &f" + plot.name) + " &7(" + plot.type().display()
                + (plot.hasPolygon() ? " &7polygon, " + plot.vertices.size() + " vertices" : "") + ")");
        c.msg("&7Value: &f" + Text.moneyPlain(plot.price)
                + (plot.forSale && plot.salePrice > 0 ? " &7| For sale: &f" + Text.moneyPlain(plot.salePrice) : "")
                + (plot.isRented() ? " &7| Rent: &f" + Text.moneyPlain(plot.rentPrice) : ""));
        c.msg("&7Owner: &f" + (plot.owned() ? nameOf(plot.owner) : "&7Unowned"));
        if (!plot.members.isEmpty()) {
            List<String> members = plot.members.stream().map(this::nameOf).toList();
            c.msg("&7Members: &f" + String.join("&7, &f", members));
        }
        if (plot.isRented() && plot.tenant != null) {
            c.msg("&7Tenant: &f" + nameOf(plot.tenant) + " &7until &f" + date(plot.rentUntil));
        }
        c.msg("&7Location: &f" + plot.minX + "," + plot.minY + "," + plot.minZ
                + " &7to &f" + plot.maxX + "," + plot.maxY + "," + plot.maxZ
                + " &7- placed blocks: &f" + plot.placed.size());
        List<String> present = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!plot.contains(online.getLocation().getBlock())) {
                continue;
            }
            String id = online.getUniqueId().toString();
            String role;
            if (plot.owner != null && plot.owner.equals(id)) {
                role = "&aOwner";
            } else if (plot.members.contains(id)) {
                role = "&bMember";
            } else if (plot.tenant != null && plot.tenant.equals(id)) {
                role = "&dTenant";
            } else {
                role = "&7Visitor";
            }
            present.add("&f" + online.getName() + " &7(" + role + "&7)");
        }
        if (present.isEmpty()) {
            c.msg("&7On plot: &fno one");
        } else {
            c.msg("&7On plot: &f" + String.join("&7, ", present));
        }
    }

    private void plotadmin(CommandContext c) {
        if (!c.isPlayer()) {
            c.msg("&6/plotadmin requires the in-game menu. Open the GUI on a player.");
            return;
        }
        Player admin = c.player();
        if (!admin.hasPermission("vicemc.properties.admin")) {
            c.error("You are not authorized.");
            return;
        }
        switch (c.arg(0)) {
            case "wand" -> giveWand(admin);
            case "pwand" -> givePolyWand(admin);
            case "type" -> setType(admin, c.arg(1));
            case "value" -> setValue(admin, c.argDouble(1, -1));
            case "info" -> info(admin, c.arg(1));
            case "remove" -> remove(admin, c.arg(1));
            case "give" -> give(admin, c.arg(1), c.playerArg(2));
            case "permit" -> permit(admin, c.playerArg(1), c.arg(2));
            case "unlist" -> unlist(admin, c.arg(1));
            case "rename" -> rename(admin, c.arg(1), c.arg(2));
            case "admin" -> gui.openAdmin(admin);
            default -> {
                if (c.size() == 0) {
                    gui.openAdmin(admin);
                } else {
                    c.usage("/plotadmin wand | pwand | type <type> | value <amount> | info <serial> | remove <serial> | give <serial> <player> | permit <player> on|off | unlist <serial> | rename <serial> <name> | admin");
                }
            }
        }
    }

    private void setType(Player admin, String value) {
        if (value == null || value.isEmpty()) {
            ctx.notifications().msg(admin, "&6Current wand type: &f" + manager.wandType().display()
                    + "&6. Change it with &e/plotadmin type house|apartment|farm|mine|shop|food_company|factory|dealership|jewelry_store|bank&6.");
            return;
        }
        PlotType type = PlotType.from(value);
        if (type == null) {
            ctx.notifications().warn(admin, "Unknown type. Use house, apartment, farm, mine, shop, food_company, factory, dealership, jewelry_store or bank.");
            return;
        }
        config.set("wand.type", type.name());
        config.save();
        ctx.notifications().msg(admin, "&aPlot wand now creates &f" + type.display()
                + "&a plots worth &f" + Text.moneyPlain(manager.wandValue()) + "&a.");
    }

    private void setValue(Player admin, double value) {
        if (value <= 0) {
            cusage(admin, "/plotadmin value <amount>");
            return;
        }
        config.set("wand.value", value);
        config.save();
        ctx.notifications().msg(admin, "&aNew plots will be worth &f" + Text.moneyPlain(value) + "&a.");
    }

    private void info(Player admin, String serial) {
        Plot plot = manager.bySerial(serial);
        if (plot == null) {
            ctx.notifications().warn(admin, "Plot not found.");
            return;
        }
        ctx.notifications().msg(admin, "&6" + plot.serial + "&7 (" + plot.type().display() + ")"
                + " - &f" + Text.moneyPlain(plot.price)
                + "&7 - Owner: &f" + (plot.owner == null ? "unowned" : nameOf(plot.owner))
                + "&7 - " + plot.minX + "," + plot.minY + "," + plot.minZ
                + " to " + plot.maxX + "," + plot.maxY + "," + plot.maxZ
                + " - Placed blocks: &f" + plot.placed.size());
    }

    private void remove(Player admin, String serial) {
        Plot plot = manager.bySerial(serial);
        if (plot == null) {
            ctx.notifications().warn(admin, "Plot not found.");
            return;
        }
        manager.clearPlot(plot, config.getBoolean("drop-items-on-sell", true));
        manager.remove(plot);
        ctx.notifications().msg(admin, "&aDeleted plot &f" + serial + "&a and cleared its interior.");
    }

    private void give(Player admin, String serial, Player target) {
        Plot plot = manager.bySerial(serial);
        if (plot == null) {
            ctx.notifications().warn(admin, "Plot not found.");
            return;
        }
        if (target == null) {
            ctx.notifications().warn(admin, "Player not found.");
            return;
        }
        manager.transfer(plot, target.getUniqueId());
        ctx.notifications().msg(admin, "&aTransferred &f" + serial + "&a to &f" + target.getName() + "&a.");
        ctx.notifications().msg(target, "&aAn admin gave you the deed to &f" + serial + "&a.");
    }

    private void permit(Player admin, Player target, String on) {
        if (target == null) {
            ctx.notifications().warn(admin, "Player not found.");
            return;
        }
        boolean enable = on == null || on.isEmpty() || !on.equalsIgnoreCase("off");
        if (on != null && on.equalsIgnoreCase("on")) {
            enable = true;
        }
        manager.setPermit(target.getUniqueId(), enable);
        ctx.notifications().msg(admin, "&aReal estate permit for &f" + target.getName()
                + "&a is now &f" + (enable ? "ON" : "OFF") + "&a.");
        if (target.isOnline()) {
            ctx.notifications().msg(target, "&aYour real estate permit is now &f" + (enable ? "ON" : "OFF")
                    + "&a. Higher limits and interior resales unlocked.");
        }
    }

    private void unlist(Player admin, String serial) {
        Plot plot = manager.bySerial(serial);
        if (plot == null) {
            ctx.notifications().warn(admin, "Plot not found.");
            return;
        }
        plot.forSale = false;
        plot.salePrice = 0;
        plot.sellWithInterior = false;
        plot.interiorManifest = "";
        manager.save(plot);
        ctx.notifications().msg(admin, "&aRemoved &f" + serial + "&a from the market.");
    }

    private void rename(Player admin, String serial, String newName) {
        Plot plot = manager.bySerial(serial);
        if (plot == null) {
            ctx.notifications().warn(admin, "Plot not found.");
            return;
        }
        if (newName == null || newName.isEmpty()) {
            ctx.notifications().warn(admin, "Usage: /plotadmin rename <serial> <name>");
            return;
        }
        plot.name = newName;
        manager.save(plot);
        ctx.notifications().msg(admin, "&aRenamed plot &f" + serial + "&a to &f" + newName + "&a.");
    }

    public void giveWand(Player player) {
        ItemStack wand = ItemBuilder.of(Material.STICK)
                .name("&6Plot Wand")
                .lore("&7Left-click: corner 1",
                        "&7Right-click: corner 2",
                        "&7Creates a &f" + manager.wandType().display()
                                + "&7 plot worth &f" + Text.moneyPlain(manager.wandValue()) + "&7.",
                        "&7Type/value: &e/plotadmin type|value")
                .tag(PlotListener.wandKey(), "true")
                .build();
        var left = player.getInventory().addItem(wand);
        if (!left.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left.values().iterator().next());
        }
        ctx.notifications().msg(player, "&aHere is your plot wand. Select two corners to create a plot.");
    }

    public void givePolyWand(Player player) {
        ItemStack wand = ItemBuilder.of(Material.GOLDEN_AXE)
                .name("&bPolygon Plot Wand")
                .lore("&7Left-click: add vertex",
                        "&7Shift+left-click air: clear selection",
                        "&7Right-click: close polygon & prompt Y height",
                        "&7Creates a polygon &f" + manager.wandType().display()
                                + "&7 plot worth &f" + Text.moneyPlain(manager.wandValue()) + "&7.",
                        "&7Type/value: &e/plotadmin type|value")
                .tag(PlotListener.polyWandKey(), "true")
                .build();
        var left = player.getInventory().addItem(wand);
        if (!left.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left.values().iterator().next());
        }
        ctx.notifications().msg(player, "&aHere is your polygon wand. Click blocks to draw the plot shape.");
    }

    private void cusage(Player player, String usage) {
        ctx.notifications().msg(player, "&eUsage: " + usage);
    }

    // --- Facade for the GUI ----------------------------------------------

    public ViceModuleContext context() {
        return ctx;
    }

    public YamlConfig config() {
        return config;
    }

    public PlotManager manager() {
        return manager;
    }

    public PromptManager prompts() {
        return prompts;
    }

    public boolean ownsAny(UUID uuid) {
        return manager.ownsAny(uuid);
    }

    public int ownedCountByType(UUID uuid, PlotType type) {
        return manager.ownedCountByType(uuid, type);
    }

    public int limit(PlotType type, UUID uuid) {
        return manager.limit(type, uuid);
    }

    public boolean hasPermit(UUID uuid) {
        return manager.hasPermit(uuid);
    }

    /** The government license required to own a business plot type, or null. */
    public BusinessType licenseFor(PlotType type) {
        return switch (type) {
            case FARM -> BusinessType.FARM;
            case MINE -> BusinessType.MINE;
            case SHOP -> BusinessType.SHOP;
            case FOOD_COMPANY -> BusinessType.FOOD_COMPANY;
            case FACTORY -> BusinessType.FACTORY;
            case DEALERSHIP -> BusinessType.DEALERSHIP;
            case JEWELRY_STORE -> BusinessType.JEWELRY_STORE;
            case BANK -> BusinessType.BANK;
            default -> null;
        };
    }

    public boolean hasLicense(Player player, PlotType type) {
        BusinessType license = licenseFor(type);
        if (license == null) {
            return true;
        }
        return business != null && business.manager().hasLicense(player.getUniqueId(), license.name());
    }

    /** A colored market line showing the license requirement, or null if none. */
    public String licenseLine(Player player, PlotType type) {
        BusinessType license = licenseFor(type);
        if (license == null) {
            return null;
        }
        return hasLicense(player, type)
                ? "&aLicense: &f" + license.display() + " &aheld"
                : "&4Requires a " + license.display() + " license";
    }

    public double balance(Player player) {
        return ctx.economy().balance(player.getUniqueId());
    }

    /** The price a buyer pays for the plot (sale price if listed, base value otherwise). */
    public double purchasePrice(Plot plot) {
        return plot.forSale && plot.salePrice > 0 ? plot.salePrice : plot.price;
    }

    public double resaleValue(Plot plot) {
        return plot.price * config.getDouble("resale-percent", 0.6);
    }

    // --- Player actions ---------------------------------------------------

    public boolean buy(Player buyer, Plot plot) {
        UUID me = buyer.getUniqueId();
        PlotType type = plot.type();
        BusinessType license = licenseFor(type);
        if (license != null && !hasLicense(buyer, type)) {
            ctx.notifications().warn(buyer, "You need a &f" + license.display()
                    + "&c license to buy a &f" + type.display().toLowerCase()
                    + "&c plot. Get one at the government building.");
            return false;
        }
        if (manager.ownedCountByType(me, type) >= manager.limit(type, me)) {
            ctx.notifications().warn(buyer, "You already own the maximum number of "
                    + type.display().toLowerCase() + " plots (" + manager.limit(type, me) + ").");
            return false;
        }
        if (plot.owner != null && !plot.forSale) {
            ctx.notifications().warn(buyer, plot.serial + " is not for sale.");
            return false;
        }
        if (plot.isRented()) {
            ctx.notifications().warn(buyer, plot.serial + " is currently rented out.");
            return false;
        }
        double cost = purchasePrice(plot);
        var payment = ctx.economy().withdraw(me, cost, "plot purchase " + plot.serial);
        if (!payment.success()) {
            ctx.notifications().warn(buyer, "You cannot afford " + Text.moneyPlain(cost) + ".");
            return false;
        }
        boolean interior = plot.forSale && plot.sellWithInterior;
        manager.finalizeSale(plot, me, interior, config.getBoolean("drop-items-on-sell", true));
        ctx.notifications().msg(buyer, "&aYou bought &f" + type.display() + " &e" + plot.serial
                + "&a for &f" + Text.moneyPlain(cost)
                + (interior ? "&a including the interior." : "&a. The plot was emptied."));
        ctx.events().publish("plot.bought", Map.of(
                "player", buyer.getName(), "serial", plot.serial,
                "type", type.name(), "price", cost, "interior", interior));
        return true;
    }

    public boolean rent(Player renter, Plot plot) {
        if (plot.owner == null || !plot.forRent || plot.isRented()) {
            ctx.notifications().warn(renter, plot.serial + " is not available for rent.");
            return false;
        }
        double cost = plot.rentPrice;
        var payment = ctx.economy().withdraw(renter.getUniqueId(), cost, "plot rent " + plot.serial);
        if (!payment.success()) {
            ctx.notifications().warn(renter, "You cannot afford " + Text.moneyPlain(cost) + ".");
            return false;
        }
        UUID ownerId = plot.ownerUuid();
        if (ownerId != null) {
            ctx.economy().deposit(ownerId, cost, "plot rent income " + plot.serial);
        }
        long until = System.currentTimeMillis() + plot.rentDays * 86400000L;
        manager.rent(plot, renter.getUniqueId(), until);
        ctx.notifications().msg(renter, "&aYou rented &f" + plot.serial + "&a for "
                + Text.moneyPlain(cost) + "&a until " + date(until) + "&a.");
        Player owner = Bukkit.getPlayer(plot.owner);
        if (owner != null) {
            ctx.notifications().msg(owner, "&6Your plot &f" + plot.serial
                    + "&6 was rented to &f" + renter.getName() + "&6 for " + Text.moneyPlain(cost) + "&6.");
        }
        ctx.events().publish("plot.rented", Map.of(
                "player", renter.getName(), "serial", plot.serial, "price", cost));
        return true;
    }

    public boolean listForSale(Player seller, Plot plot, double price, boolean withInterior) {
        if (!plot.owned() || !plot.owner.equals(seller.getUniqueId().toString())) {
            ctx.notifications().warn(seller, "Only the owner can sell this plot.");
            return false;
        }
        if (plot.isRented()) {
            ctx.notifications().warn(seller, "Cancel the rental of " + plot.serial + " before selling it.");
            return false;
        }
        if (withInterior && !manager.hasPermit(seller.getUniqueId())) {
            ctx.notifications().warn(seller, "Selling with the interior requires a real estate permit.");
            return false;
        }
        plot.forSale = true;
        plot.salePrice = Math.max(0, price);
        plot.sellWithInterior = withInterior;
        plot.interiorManifest = withInterior ? manager.buildManifest(plot) : "";
        plot.forRent = false;
        plot.tenant = null;
        plot.rentUntil = 0;
        manager.save(plot);
        ctx.notifications().msg(seller, "&aListed &f" + plot.serial + "&a for sale at "
                + Text.moneyPlain(price) + (withInterior ? "&a with the interior." : "&a (empty)."));
        return true;
    }

    public boolean unlistForSale(Player player, Plot plot) {
        if (!plot.owned() || !plot.owner.equals(player.getUniqueId().toString())) {
            ctx.notifications().warn(player, "Only the owner can unlist this plot.");
            return false;
        }
        plot.forSale = false;
        plot.salePrice = 0;
        plot.sellWithInterior = false;
        plot.interiorManifest = "";
        manager.save(plot);
        ctx.notifications().msg(player, "&aRemoved &f" + plot.serial + "&a from the market.");
        return true;
    }

    public boolean listForRent(Player owner, Plot plot, double price, long days) {
        if (!plot.owned() || !plot.owner.equals(owner.getUniqueId().toString())) {
            ctx.notifications().warn(owner, "Only the owner can rent out this plot.");
            return false;
        }
        plot.forRent = true;
        plot.rentPrice = Math.max(0, price);
        plot.rentDays = Math.max(1, days);
        plot.forSale = false;
        plot.salePrice = 0;
        plot.sellWithInterior = false;
        plot.interiorManifest = "";
        manager.save(plot);
        ctx.notifications().msg(owner, "&aListed &f" + plot.serial + "&a for rent at "
                + Text.moneyPlain(price) + " per " + days + " day(s).");
        return true;
    }

    public boolean cancelRent(Player owner, Plot plot) {
        if (!plot.owned() || !plot.owner.equals(owner.getUniqueId().toString())) {
            ctx.notifications().warn(owner, "Only the owner can cancel the rental.");
            return false;
        }
        manager.cancelRent(plot, config.getBoolean("drop-items-on-sell", true));
        ctx.notifications().msg(owner, "&aRental of &f" + plot.serial + "&a cancelled. "
                + "The tenant's furniture was removed.");
        return true;
    }

    public boolean sellBack(Player seller, Plot plot) {
        if (!plot.owned() || !plot.owner.equals(seller.getUniqueId().toString())) {
            ctx.notifications().warn(seller, "Only the owner can sell this plot.");
            return false;
        }
        if (plot.isRented()) {
            ctx.notifications().warn(seller, "Cancel the rental of " + plot.serial + " before selling it back.");
            return false;
        }
        double value = resaleValue(plot);
        var payment = ctx.economy().deposit(seller.getUniqueId(), value, "plot sale " + plot.serial);
        if (!payment.success()) {
            ctx.notifications().warn(seller, "Sale failed: " + payment.detail());
            return false;
        }
        manager.sellBack(plot, config.getBoolean("drop-items-on-sell", true));
        ctx.notifications().msg(seller, "&aYou sold &f" + plot.serial + "&a back for "
                + Text.moneyPlain(value) + "&a. The plot was emptied.");
        return true;
    }

    public boolean addMember(Player owner, Plot plot, String targetName) {
        if (!plot.owned() || !plot.owner.equals(owner.getUniqueId().toString())) {
            ctx.notifications().warn(owner, "Only the owner can manage members.");
            return false;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            target = Bukkit.getPlayer(targetName);
        }
        if (target == null) {
            ctx.notifications().warn(owner, "Player '" + targetName + "' is not online.");
            return false;
        }
        String id = target.getUniqueId().toString();
        if (plot.members.contains(id)) {
            ctx.notifications().warn(owner, target.getName() + " is already a member.");
            return false;
        }
        plot.members.add(id);
        manager.save(plot);
        ctx.notifications().msg(owner, "&aAdded &f" + target.getName() + "&a to &f" + plot.serial + "&a.");
        ctx.notifications().msg(target, "&aYou can now build in &f" + plot.serial + "&a.");
        return true;
    }

    public boolean removeMember(Player owner, Plot plot, String targetName) {
        if (!plot.owned() || !plot.owner.equals(owner.getUniqueId().toString())) {
            ctx.notifications().warn(owner, "Only the owner can manage members.");
            return false;
        }
        String id = playerIdByName(targetName);
        if (id == null || !plot.members.contains(id)) {
            ctx.notifications().warn(owner, "No member named '" + targetName + "'.");
            return false;
        }
        plot.members.remove(id);
        manager.save(plot);
        ctx.notifications().msg(owner, "&aRemoved &f" + targetName + "&a from &f" + plot.serial + "&a.");
        return true;
    }

    public void teleportTo(Player player, Plot plot) {
        if (plot.bukkitWorld() == null) {
            ctx.notifications().warn(player, "That plot's world is not loaded.");
            return;
        }
        double x = (plot.minX + plot.maxX) / 2.0 + 0.5;
        double z = (plot.minZ + plot.maxZ) / 2.0 + 0.5;
        player.teleport(new org.bukkit.Location(plot.bukkitWorld(), x, plot.minY, z));
        ctx.notifications().msg(player, "&aWelcome to &f" + plot.serial + "&a.");
    }

    // --- Membership -------------------------------------------------------

    /** A member removing themselves from a plot. Owners cannot leave. */
    public boolean leavePlot(Player player, Plot plot) {
        String id = player.getUniqueId().toString();
        if (id.equals(plot.owner)) {
            ctx.notifications().warn(player, "You own " + plot.serial + " - you cannot leave your own plot.");
            return false;
        }
        if (!plot.members.remove(id)) {
            ctx.notifications().warn(player, "You are not a member of " + plot.serial + ".");
            return false;
        }
        manager.save(plot);
        ctx.notifications().msg(player, "&aYou left &f" + plot.serial + "&a.");
        return true;
    }

    // --- Rentals ----------------------------------------------------------

    public boolean unlistForRent(Player owner, Plot plot) {
        if (!plot.owned() || !plot.owner.equals(owner.getUniqueId().toString())) {
            ctx.notifications().warn(owner, "Only the owner can unlist this plot.");
            return false;
        }
        plot.forRent = false;
        plot.rentPrice = 0;
        plot.rentDays = 0;
        manager.save(plot);
        ctx.notifications().msg(owner, "&aRemoved &f" + plot.serial + "&a from the rental market.");
        return true;
    }

    public boolean renewRental(Player renter, Plot plot) {
        if (plot.tenant == null || !plot.tenant.equals(renter.getUniqueId().toString())) {
            ctx.notifications().warn(renter, "You are not renting " + plot.serial + ".");
            return false;
        }
        double cost = plot.rentPrice;
        var payment = ctx.economy().withdraw(renter.getUniqueId(), cost, "plot rent renew " + plot.serial);
        if (!payment.success()) {
            ctx.notifications().warn(renter, "You cannot afford " + Text.moneyPlain(cost) + ".");
            return false;
        }
        UUID ownerId = plot.ownerUuid();
        if (ownerId != null) {
            ctx.economy().deposit(ownerId, cost, "plot rent income " + plot.serial);
        }
        long base = Math.max(System.currentTimeMillis(), plot.rentUntil);
        plot.rentUntil = base + plot.rentDays * 86400000L;
        manager.save(plot);
        ctx.notifications().msg(renter, "&aYou renewed &f" + plot.serial + "&a until &f" + date(plot.rentUntil) + "&a.");
        return true;
    }

    public boolean abandonRental(Player renter, Plot plot) {
        if (plot.tenant == null || !plot.tenant.equals(renter.getUniqueId().toString())) {
            ctx.notifications().warn(renter, "You are not renting " + plot.serial + ".");
            return false;
        }
        manager.clearPlacedBy(plot, plot.tenant, config.getBoolean("drop-items-on-sell", true));
        plot.tenant = null;
        plot.rentUntil = 0;
        plot.forRent = false;
        manager.save(plot);
        ctx.notifications().msg(renter, "&aYou abandoned the rental of &f" + plot.serial + "&a.");
        Player owner = Bukkit.getPlayer(plot.owner);
        if (owner != null) {
            ctx.notifications().msg(owner, "&6" + renter.getName() + " abandoned the rental of &f" + plot.serial + "&6.");
        }
        return true;
    }

    // --- Plot border ------------------------------------------------------

    public void toggleBorder(Player player, Plot plot) {
        String key = player.getUniqueId().toString();
        if (borderEnabled.remove(key)) {
            ctx.notifications().msg(player, "&7Plot border &coff&7.");
            return;
        }
        borderEnabled.add(key);
        listener.showBorder(plot, player);
        ctx.notifications().msg(player, "&aPlot border &aon&a - golden edges while you are inside.");
    }

    public boolean borderEnabled(UUID uuid) {
        return borderEnabled.contains(uuid.toString());
    }

    public void showBorder(Plot plot, Player viewer) {
        listener.showBorder(plot, viewer);
    }

    // --- Admin facade -----------------------------------------------------

    public void setWandValue(double value) {
        config.set("wand.value", value);
        config.save();
    }

    public void setWandType(PlotType type) {
        config.set("wand.type", type.name());
        config.save();
    }

    public void adminRemovePlot(Plot plot) {
        manager.clearPlot(plot, config.getBoolean("drop-items-on-sell", true));
        manager.remove(plot);
    }

    public void grantPermit(UUID uuid, boolean on) {
        manager.setPermit(uuid, on);
    }

    // --- Helpers ----------------------------------------------------------

    public String nameOf(String uuidString) {
        if (uuidString == null) {
            return "unknown";
        }
        try {
            UUID uuid = UUID.fromString(uuidString);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                return online.getName();
            }
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            return name == null ? uuidString.substring(0, 8) : name;
        } catch (IllegalArgumentException ex) {
            return uuidString;
        }
    }

    public String playerIdByName(String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            target = Bukkit.getPlayer(name);
        }
        if (target != null) {
            return target.getUniqueId().toString();
        }
        for (Plot plot : manager.all()) {
            for (String member : plot.members) {
                if (nameOf(member).equalsIgnoreCase(name)) {
                    return member;
                }
            }
        }
        return null;
    }

    public String date(long epoch) {
        return new java.text.SimpleDateFormat("dd-MM-yyyy HH:mm").format(new java.util.Date(epoch));
    }

    private List<String> serials() {
        return manager.all().stream().map(p -> p.serial).toList();
    }

    private List<String> playerNames() {
        return new ArrayList<>(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
    }
}
