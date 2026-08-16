package net.vicemc.modules.business;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business ownership, factory/farm production and dealership/shop operations.
 */
public final class BusinessModule implements ViceModule {

    private ViceModuleContext ctx;
    private YamlConfig config;
    private BusinessManager manager;
    private ProductionService production;
    private BusinessGui gui;

    @Override
    public String id() {
        return "business";
    }

    @Override
    public String displayName() {
        return "Vice Business";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    public BusinessManager manager() {
        return manager;
    }

    public ProductionService production() {
        return production;
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("chains.yml");
        this.manager = new BusinessManager(ctx);
        this.production = new ProductionService(ctx, config, manager);
        this.gui = new BusinessGui(ctx, this);

        registerCommands();
        ctx.logger().info("Business module ready.");
    }

    private void registerCommands() {
        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("business")
                .aliases("biz")
                .description("Manage your businesses")
                .executes(this::business)
                .tabulates((c, a) -> a.size() <= 1 ? List.of("info", "list", "sell", "employees") : List.of())
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("factory")
                .description("Factory production")
                .executes(this::factory)
                .tabulates((c, a) -> a.size() <= 1 ? List.of("list", "craft") : production.recipeIds())
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("farm")
                .description("Farm operations")
                .executes(this::farm)
                .tabulates((c, a) -> a.size() <= 1 ? List.of("harvest", "sell") : List.of())
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("shop")
                .description("Government stock and shops")
                .executes(this::shop)
                .tabulates((c, a) -> a.size() <= 1 ? List.of("stock", "buy") : production.stockIds())
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("dealer")
                .description("Vehicle dealership")
                .executes(this::dealer)
                .tabulates((c, a) -> a.size() <= 1 ? List.of("sell") : playerNames())
                .build());
    }

    // --- GUI facade -------------------------------------------------------

    public ViceModuleContext context() {
        return ctx;
    }

    public YamlConfig businessConfig() {
        return config;
    }

    /**
     * Free business registration issued by the government counter. No money is
     * charged; license-required types still need a government license first.
     */
    public boolean createBusiness(Player player, BusinessType type, String name) {
        UUID owner = player.getUniqueId();
        if (manager.isBusinessBanned(owner)) {
            player.sendMessage(Text.color("&cYou are banned from starting businesses: " + manager.banReason(owner)));
            return false;
        }
        String safeName = name == null || name.isBlank() ? type.display() : name;
        if (config.getStringList("license-required").contains(type.name())
                && !manager.hasLicense(owner, type.name())) {
            player.sendMessage(Text.color("&cCreating a " + type.display() + " requires a government license."));
            return false;
        }
        if (manager.ownedOfType(owner, type.name()) != null) {
            player.sendMessage(Text.color("&cYou already own a " + type.display() + "."));
            return false;
        }
        Business business = manager.create(type, safeName, owner);
        player.sendMessage(Text.color("&aRegistered &f" + type.display() + " &a'&f" + safeName
                + "&a' (ID " + business.id + ")."));
        return true;
    }

    /**
     * Government-serving variant: registers a business for {@code owner} (the
     * citizen being served) while the requesting employee sees the result.
     */
    public boolean createBusinessFor(Player actor, UUID owner, BusinessType type, String name) {
        if (manager.isBusinessBanned(owner)) {
            actor.sendMessage(Text.color("&c" + offlineName(owner) + " is banned from starting businesses: "
                    + manager.banReason(owner)));
            return false;
        }
        String safeName = name == null || name.isBlank() ? type.display() : name;
        if (config.getStringList("license-required").contains(type.name())
                && !manager.hasLicense(owner, type.name())) {
            actor.sendMessage(Text.color("&cCreating a " + type.display() + " for them requires a government license."));
            return false;
        }
        if (manager.ownedOfType(owner, type.name()) != null) {
            actor.sendMessage(Text.color("&cThey already own a " + type.display() + "."));
            return false;
        }
        Business business = manager.create(type, safeName, owner);
        actor.sendMessage(Text.color("&aRegistered &f" + type.display() + " &a'&f" + safeName
                + "&a' (ID " + business.id + ") for " + offlineName(owner) + "."));
        Player target = Bukkit.getPlayer(owner);
        if (target != null) {
            target.sendMessage(Text.color("&aThe government registered your " + type.display()
                    + " &a'&f" + safeName + "&a' (ID " + business.id + ")."));
        }
        return true;
    }

    public boolean createBusiness(Player player, BusinessType type) {
        return createBusiness(player, type, type.display());
    }

    public void addEmployee(Player player, int id, UUID target) {
        Business business = manager.byId(id).orElse(null);
        if (business == null || !business.owner.equals(player.getUniqueId())) {
            player.sendMessage(Text.color("&cBusiness not found or not owned by you."));
            return;
        }
        business.employees.add(target);
        manager.save();
        player.sendMessage(Text.color("&aAdded " + offlineName(target) + " as an employee."));
    }

    public void removeEmployee(Player player, int id, UUID target) {
        Business business = manager.byId(id).orElse(null);
        if (business == null || !business.owner.equals(player.getUniqueId())) {
            player.sendMessage(Text.color("&cBusiness not found or not owned by you."));
            return;
        }
        business.employees.remove(target);
        manager.save();
        player.sendMessage(Text.color("&aRemoved " + offlineName(target) + "."));
    }

    // --- /business --------------------------------------------------------

    private void business(CommandContext c) {
        switch (c.arg(0)) {
            case "create" -> c.msg("&eBusinesses are no longer registered here. Visit a government "
                    + "employee at the government building to register a business.");
            case "info" -> info(c);
            case "list" -> list(c);
            case "sell" -> sell(c);
            case "employees" -> employees(c);
            default -> gui.openDashboard(c.player());
        }
    }

    private void info(CommandContext c) {
        Business business = null;
        if (c.size() > 1) {
            business = manager.byId(c.argInt(1, -1)).orElse(null);
        } else if (c.isPlayer()) {
            List<Business> owned = manager.byOwner(c.player().getUniqueId());
            if (!owned.isEmpty()) {
                business = owned.get(0);
            }
        }
        if (business == null) {
            c.error("Business not found.");
            return;
        }
        c.msg("&6#" + business.id + " &f" + business.name + "&7 [" + business.type + "]");
        c.msg("  &7Owner: &f" + offlineName(business.owner) + " &7Licensed: " + (business.licensed ? "&aYes" : "&cNo"));
        c.msg("  &7Employees: &f" + business.employees.size() + " &7Stock: &f" + business.stock);
    }

    private void list(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        List<Business> owned = manager.byOwner(c.player().getUniqueId());
        if (owned.isEmpty()) {
            c.msg("&7You own no businesses. Use &e/business create&7.");
            return;
        }
        owned.forEach(b -> c.msg("  &8#" + b.id + " &f" + b.name + "&7 [" + b.type + "]"
                + (b.licensed ? " &a[licensed]" : " &c[unlicensed]")));
    }

    private void sell(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (c.size() < 2) {
            c.usage("/business sell <product> <count>");
            return;
        }
        int count = c.argInt(2, 64);
        production.sellProduct(c.player(), c.arg(1), count);
    }

    private void employees(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (c.size() < 4) {
            c.usage("/business employees <add|remove> <id> <player>");
            return;
        }
        Business business = manager.byId(c.argInt(2, -1)).orElse(null);
        if (business == null || !business.owner.equals(c.player().getUniqueId())) {
            c.error("Business not found or not owned by you.");
            return;
        }
        Player target = c.playerArg(3);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        if ("add".equals(c.arg(1))) {
            business.employees.add(target.getUniqueId());
            manager.save();
            c.msg("&aAdded " + target.getName() + " as an employee.");
        } else if ("remove".equals(c.arg(1))) {
            business.employees.remove(target.getUniqueId());
            manager.save();
            c.msg("&aRemoved " + target.getName() + ".");
        }
    }

    // --- /factory ---------------------------------------------------------

    private void factory(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        switch (c.arg(0)) {
            case "list" -> {
                c.msg("&6Factory recipes:");
                production.recipeIds().forEach(id ->
                        c.msg("  &f" + id + " &7- " + production.recipeDisplay(id)));
            }
            case "craft" -> {
                if (c.size() < 2) {
                    c.usage("/factory craft <recipe> [count]");
                    return;
                }
                production.craft(c.player(), c.arg(1), c.argInt(2, 1));
            }
            default -> c.msg("&6/factory list | craft <recipe> [count]");
        }
    }

    // --- /farm ------------------------------------------------------------

    private void farm(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        switch (c.arg(0)) {
            case "harvest" -> production.farmHarvest(c.player());
            case "sell" -> production.farmSell(c.player());
            default -> c.msg("&6/farm harvest | sell");
        }
    }

    // --- /shop ------------------------------------------------------------

    private void shop(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        switch (c.arg(0)) {
            case "stock" -> {
                c.msg("&6Government stock (licensed businesses only):");
                production.stockIds().forEach(id -> c.msg("  " + production.stockInfo(id)));
            }
            case "buy" -> {
                if (c.size() < 2) {
                    c.usage("/shop buy <stockId> [count]");
                    return;
                }
                production.buyStock(c.player(), c.arg(1), c.argInt(2, 1));
            }
            default -> c.msg("&6/shop stock | buy <stockId> [count]");
        }
    }

    // --- /dealer ----------------------------------------------------------

    private void dealer(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (c.size() < 3) {
            c.usage("/dealer sell <player> <stockId>");
            return;
        }
        Player customer = c.playerArg(1);
        if (customer == null) {
            c.error("Customer not found.");
            return;
        }
        production.dealerSell(c.player(), customer, c.arg(2));
    }

    private String offlineName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        return Bukkit.getOfflinePlayer(uuid).getName() == null ? "unknown" : Bukkit.getOfflinePlayer(uuid).getName();
    }

    private List<String> playerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }
}
