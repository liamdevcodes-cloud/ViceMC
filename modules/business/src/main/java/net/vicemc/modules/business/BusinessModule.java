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
    private BusinessPrompts prompts;
    private RoleService roleService;
    private SupplyShopService supplyShop;
    private WorkPlaytimeService workTime;
    private SalaryService salary;
    private BusinessAdminGui adminGui;

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

    public BusinessPrompts prompts() {
        return prompts;
    }

    public RoleService roleService() {
        return roleService;
    }

    public SupplyShopService supplyShop() {
        return supplyShop;
    }

    public WorkPlaytimeService workTime() {
        return workTime;
    }

    public SalaryService salary() {
        return salary;
    }

    public BusinessAdminGui adminGui() {
        return adminGui;
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("chains.yml");
        this.manager = new BusinessManager(ctx);
        this.production = new ProductionService(ctx, config, manager);

        this.roleService = new RoleService(ctx, manager);
        this.supplyShop = new SupplyShopService(ctx, manager, config);
        this.workTime = new WorkPlaytimeService(ctx, manager);
        this.salary = new SalaryService(ctx, manager, roleService, workTime);

        this.prompts = new BusinessPrompts(this);
        this.gui = new BusinessGui(ctx, this);
        this.adminGui = new BusinessAdminGui(ctx, this);

        Bukkit.getPluginManager().registerEvents(prompts, ctx.plugin());

        Bukkit.getScheduler().runTaskTimer(ctx.plugin(), (Runnable) workTime::tickAll, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(ctx.plugin(), (Runnable) salary::paySalaries, 72000L, 72000L);

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

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("businessadmin")
                .aliases("bizadmin")
                .description("Business admin panel")
                .permission("vicemc.business.admin")
                .executes(c -> {
                    if (!c.isPlayer()) {
                        return;
                    }
                    adminGui.openAdminDashboard(c.player());
                })
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
            case "admin" -> businessAdmin(c);
            default -> gui.openDashboard(c.player());
        }
    }

    // --- /business admin ---------------------------------------------------

    private void businessAdmin(CommandContext c) {
        if (!c.isPlayer() || !c.player().hasPermission("vicemc.business.admin")) {
            c.error("You are not authorized.");
            return;
        }
        if (c.size() < 2) {
            c.msg("&6/business admin grant <player> <type> | create <player> <type> [name]");
            return;
        }
        switch (c.arg(1)) {
            case "grant" -> adminGrantLicense(c);
            case "create" -> adminCreateBusiness(c);
            case "forceemployee" -> adminForceEmployee(c);
            default -> c.msg("&6/business admin grant <player> <type> | create <player> <type> [name] | forceemployee <player> <businessId> <role>");
        }
    }

    private void adminGrantLicense(CommandContext c) {
        if (c.size() < 4) {
            c.usage("/business admin grant <player> <type>");
            return;
        }
        UUID target = c.uuidArg(2);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        String type = c.arg(3).toUpperCase();
        manager.grantLicense(target, type);
        c.msg("&aGranted &f" + type + "&a license to &f" + c.arg(2) + "&a (admin bypass).");
        Player p = Bukkit.getPlayer(target);
        if (p != null) {
            ctx.notifications().msg(p, "&aAn admin granted you a " + type + " license.");
        }
    }

    private void adminCreateBusiness(CommandContext c) {
        if (c.size() < 4) {
            c.usage("/business admin create <player> <type> [name]");
            return;
        }
        UUID owner = c.uuidArg(2);
        if (owner == null) {
            c.error("Player not found.");
            return;
        }
        String typeName = c.arg(3).toUpperCase();
        BusinessType type;
        try {
            type = BusinessType.valueOf(typeName);
        } catch (IllegalArgumentException ex) {
            c.error("Unknown business type '" + typeName + "'.");
            return;
        }
        String name = c.size() > 4
                ? String.join(" ", java.util.Arrays.copyOfRange(c.args(), 4, c.size()))
                : type.display();
        if (manager.ownedOfType(owner, type.name()) != null) {
            c.error(offlineName(owner) + " already owns a " + type.display() + ".");
            return;
        }
        Business business = manager.create(type, name, owner);
        business.licensed = true;
        manager.save();
        c.msg("&aCreated &f" + type.display() + " &a'" + name + "&a' (ID " + business.id
                + ") for &f" + offlineName(owner) + "&a (admin bypass).");
        Player p = Bukkit.getPlayer(owner);
        if (p != null) {
            ctx.notifications().msg(p, "&aAn admin created your " + type.display()
                    + " '" + name + "' (ID " + business.id + ").");
        }
    }

    private void adminForceEmployee(CommandContext c) {
        if (c.size() < 5) {
            c.usage("/business admin forceemployee <player> <businessId> <role>");
            return;
        }
        UUID target = c.uuidArg(2);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        Business business = manager.byId(c.argInt(3, -1)).orElse(null);
        if (business == null) {
            c.error("Business not found.");
            return;
        }
        String roleName = c.arg(4);
        business.employees.add(target);
        if (!roleName.equalsIgnoreCase("none")) {
            business.roleAssignments.put(target.toString(), roleName);
        }
        manager.save();
        c.msg("&aAdded &f" + offlineName(target) + "&a to &f" + business.name + "&a as &f" + roleName + "&a.");
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
