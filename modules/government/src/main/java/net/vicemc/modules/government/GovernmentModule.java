package net.vicemc.modules.government;

import com.google.gson.reflect.TypeToken;
import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.Json;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import net.vicemc.core.ViceCore;
import net.vicemc.modules.business.Business;
import net.vicemc.modules.business.BusinessModule;
import net.vicemc.modules.business.BusinessType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The government regulates the economy and, through its automated employees
 * at the government building, issues licenses and registrations to citizens.
 * Player-facing services only work inside the region tagged in government.yml.
 */
public final class GovernmentModule implements ViceModule, Listener {

    private static final TypeToken<List<SubsidyRequest>> REQUEST_LIST = new TypeToken<List<SubsidyRequest>>() {
    };

    private ViceModuleContext ctx;
    private YamlConfig config;
    private BusinessModule business;
    private GovernmentGui gui;
    private GovernmentCounterGui counterGui;
    private GovernmentPrompts prompts;
    private String regionTag = "government";

    @Override
    public String id() {
        return "government";
    }

    @Override
    public String displayName() {
        return "Vice Government";
    }

    @Override
    public String version() {
        return "1.3.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("government.yml");
        this.business = (BusinessModule) ViceCore.get().getModuleRegistry().module("business").orElse(null);
        if (business == null) {
            ctx.logger().warning("Business module not found; government functions will be limited.");
        }
        this.regionTag = config.getString("region-tag", "government");
        this.gui = new GovernmentGui(ctx, this);
        this.counterGui = new GovernmentCounterGui(ctx, this);
        this.prompts = new GovernmentPrompts(this);

        Bukkit.getPluginManager().registerEvents(this, ctx.plugin());
        Bukkit.getPluginManager().registerEvents(prompts, ctx.plugin());

        registerCommands();

        if (ctx.regions().byTag(regionTag).isEmpty()) {
            ctx.logger().warning("No region tagged '" + regionTag + "' found. Create the government building "
                    + "with /vregion pos1 + pos2 + create <id> " + regionTag
                    + ". Government counter services are disabled until then.");
        }

        ctx.logger().info("Government module ready.");
    }

    @Override
    public void onDisable() {
    }

    private void registerCommands() {
        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("license")
                .description("Manage business licenses")
                .executes(this::license)
                .tabulates((c, a) -> a.size() <= 1 ? List.of("issue", "revoke", "list") : playerNames())
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("gov")
                .description("Government operations")
                .executes(this::gov)
                .tabulates((c, a) -> {
                    if (a.size() <= 1) {
                        return List.of("subsidy", "seize", "businessban", "pardon", "flag", "requests", "treasury", "serve");
                    }
                    if ("requests".equals(a.get(0)) && a.size() == 2) {
                        return List.of("list", "approve", "deny");
                    }
                    return playerNames();
                })
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("govcounter")
                .description("Open the government counter (inside the government building)")
                .executes(this::govcounter)
                .build());
    }

    // --- GUI facade -------------------------------------------------------

    public ViceModuleContext context() {
        return ctx;
    }

    public YamlConfig govConfig() {
        return config;
    }

    public BusinessModule businessModule() {
        return business;
    }

    public GovernmentPrompts prompts() {
        return prompts;
    }

    public String regionTag() {
        return regionTag;
    }

    public void openPicker(Player employee) {
        counterGui.openPicker(employee);
    }

    public void issueLicense(Player gov, UUID target, String type) {
        business.manager().grantLicense(target, type);
        gov.sendMessage(Text.color("&aIssued " + type + " license to " + nameOf(target) + "."));
        Player p = Bukkit.getPlayer(target);
        if (p != null) {
            ctx.notifications().msg(p, "&aThe government issued you a " + type + " license.");
        }
    }

    public void revokeLicense(Player gov, UUID target, String type) {
        business.manager().revokeLicense(target, type);
        gov.sendMessage(Text.color("&aRevoked " + type + " license from " + nameOf(target) + "."));
    }

    public void grantLicenseAtCounter(Player player, String type) {
        double cost = licenseCost(type);
        if (cost > 0) {
            var result = ctx.economy().withdraw(player.getUniqueId(), cost, "license fee");
            if (!result.success()) {
                ctx.notifications().warn(player, "The " + type + " license costs "
                        + Text.moneyPlain(cost) + " and you cannot afford it.");
                return;
            }
            addTreasury(cost);
        }
        business.manager().grantLicense(player.getUniqueId(), type);
        ctx.notifications().msg(player, "&aThe government sold you a " + type + " license"
                + (cost > 0 ? " for " + Text.moneyPlain(cost) + "." : "."));
        ctx.events().publish("government", Map.of("type", "license", "target", player.getName(), "license", type, "cost", cost));
    }

    public double licenseCost(String type) {
        return config.getDouble("license-costs." + type, 0);
    }

    /**
     * Counter-serving variant: the government employee sells a license to the
     * citizen {@code target}. The fee is charged to the citizen and goes to
     * the treasury. Notifies both employee and citizen.
     */
    public void sellLicenseTo(Player employee, UUID target, String type) {
        if (business.manager().hasLicense(target, type)) {
            ctx.notifications().msg(employee, nameOf(target) + " already holds a " + type + " license.");
            return;
        }
        double cost = licenseCost(type);
        if (cost > 0) {
            var result = ctx.economy().withdraw(target, cost, "license fee");
            if (!result.success()) {
                ctx.notifications().warn(employee, nameOf(target) + " cannot afford the " + type
                        + " license: " + Text.moneyPlain(cost) + ".");
                return;
            }
            addTreasury(cost);
        }
        business.manager().grantLicense(target, type);
        ctx.notifications().msg(employee, "&aSold a " + type + " license to " + nameOf(target)
                + (cost > 0 ? " for " + Text.moneyPlain(cost) + "." : "."));
        Player citizen = Bukkit.getPlayer(target);
        if (citizen != null) {
            ctx.notifications().msg(citizen, "&aThe government sold you a " + type + " license"
                    + (cost > 0 ? " for " + Text.moneyPlain(cost) + "." : "."));
        }
        ctx.events().publish("government", Map.of("type", "license", "target", nameOf(target),
                "license", type, "cost", cost, "employee", employee.getName()));
    }

    public double treasury() {
        return ctx.storage().getModuleData("government", "treasury")
                .map(json -> Json.fromJson(json, Double.class))
                .orElse(0.0);
    }

    private void addTreasury(double amount) {
        ctx.storage().setModuleData("government", "treasury", Json.toJson(treasury() + amount));
    }

    public boolean grantSubsidy(Player gov, int businessId, double amount) {
        Business target = business.manager().byId(businessId).orElse(null);
        if (target == null) {
            gov.sendMessage(Text.color("&cBusiness not found."));
            return false;
        }
        double safe = Math.max(config.getInt("subsidy-min", 20000),
                Math.min(amount, config.getInt("subsidy-max", 40000)));
        Player owner = Bukkit.getPlayer(target.owner);
        if (owner == null) {
            gov.sendMessage(Text.color("&cBusiness owner is offline."));
            return false;
        }
        int granted = grantGoods(owner, safe);
        gov.sendMessage(Text.color("&aGranted a subsidy worth &f" + Text.moneyPlain(safe)
                + "&a (goods) to &f" + target.name + "&a (" + granted + " items)."));
        ctx.notifications().msg(owner, "&aThe government approved a subsidy for your business '"
                + target.name + "' worth " + Text.moneyPlain(safe) + " in goods.");
        ctx.events().publish("government", Map.of("type", "subsidy", "business", target.name, "amount", safe));
        return true;
    }

    public boolean seize(Player gov, UUID target, double amount) {
        if (amount <= 0) {
            gov.sendMessage(Text.color("&cInvalid amount."));
            return false;
        }
        var result = ctx.economy().withdraw(target, amount, "asset seizure");
        if (!result.success()) {
            gov.sendMessage(Text.color("&cSeizure failed: " + result.detail()));
            return false;
        }
        gov.sendMessage(Text.color("&aSeized &f" + Text.moneyPlain(amount) + "&a from " + nameOf(target) + " for unpaid debts."));
        ctx.events().publish("government", Map.of("type", "seizure", "target", nameOf(target), "amount", amount));
        return true;
    }

    public void businessBan(Player gov, UUID target, String reason) {
        business.manager().banFromBusiness(target, reason);
        gov.sendMessage(Text.color("&cBanned " + nameOf(target) + " from starting new businesses."));
        ctx.events().publish("government", Map.of("type", "businessban", "target", nameOf(target), "reason", reason));
    }

    public void pardon(Player gov, UUID target) {
        business.manager().pardonBusiness(target);
        gov.sendMessage(Text.color("&aPardoned " + nameOf(target) + " - they may start businesses again."));
    }

    private String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    // --- Subsidy requests -------------------------------------------------

    /**
     * Counter-serving variant: the government employee files a subsidy request
     * for the citizen {@code target} on their owned business.
     */
    public void submitSubsidyRequestFor(Player employee, UUID target, int businessId, double amount, String reason) {
        Business targetBusiness = business.manager().byId(businessId).orElse(null);
        if (targetBusiness == null || !targetBusiness.owner.equals(target)) {
            ctx.notifications().warn(employee, nameOf(target) + " does not own that business.");
            return;
        }
        List<SubsidyRequest> list = requests();
        SubsidyRequest req = new SubsidyRequest();
        req.id = list.stream().mapToInt(r -> r.id).max().orElse(0) + 1;
        req.playerName = nameOf(target);
        req.playerUuid = target.toString();
        req.businessId = businessId;
        req.businessName = targetBusiness.name;
        req.amount = amount;
        req.reason = reason;
        req.createdAt = System.currentTimeMillis();
        list.add(req);
        saveRequests(list);
        ctx.notifications().msg(employee, "&aSubsidy request #" + req.id + " for '&f" + targetBusiness.name
                + "&a' submitted on behalf of " + nameOf(target) + ". A government official will review it.");
        Player citizen = Bukkit.getPlayer(target);
        if (citizen != null) {
            ctx.notifications().msg(citizen, "&aThe government submitted a subsidy request for your business '&f"
                    + targetBusiness.name + "&a' worth " + Text.moneyPlain(amount) + ".");
        }
    }

    public void submitSubsidyRequest(Player player, int businessId, double amount, String reason) {
        Business target = business.manager().byId(businessId).orElse(null);
        if (target == null || !target.owner.equals(player.getUniqueId())) {
            ctx.notifications().warn(player, "You can only request a subsidy for a business you own.");
            return;
        }
        List<SubsidyRequest> list = requests();
        SubsidyRequest req = new SubsidyRequest();
        req.id = list.stream().mapToInt(r -> r.id).max().orElse(0) + 1;
        req.playerName = player.getName();
        req.playerUuid = player.getUniqueId().toString();
        req.businessId = businessId;
        req.businessName = target.name;
        req.amount = amount;
        req.reason = reason;
        req.createdAt = System.currentTimeMillis();
        list.add(req);
        saveRequests(list);
        ctx.notifications().msg(player, "&aSubsidy request #" + req.id + " for '&f" + target.name
                + "&a' submitted. A government official will review it.");
    }

    public void listRequests(Player gov) {
        List<SubsidyRequest> pending = requests().stream().filter(r -> "PENDING".equals(r.status)).toList();
        if (pending.isEmpty()) {
            gov.sendMessage(Text.color("&7No pending subsidy requests."));
            return;
        }
        gov.sendMessage(Text.color("&6Pending subsidy requests:"));
        for (SubsidyRequest r : pending) {
            gov.sendMessage(Text.color("  &8#" + r.id + " &f" + r.playerName + " &7for &f" + r.businessName
                    + " &7requested &f" + Text.moneyPlain(r.amount)
                    + "&7: &f" + (r.reason.isEmpty() ? "-" : r.reason)));
        }
    }

    public void approveRequest(Player gov, int id) {
        List<SubsidyRequest> list = requests();
        SubsidyRequest req = list.stream().filter(r -> r.id == id).findFirst().orElse(null);
        if (req == null) {
            gov.sendMessage(Text.color("&cNo subsidy request with id #" + id + "."));
            return;
        }
        if ("APPROVED".equals(req.status)) {
            gov.sendMessage(Text.color("&cRequest #" + id + " is already approved."));
            return;
        }
        req.status = "APPROVED";
        saveRequests(list);
        Player owner = Bukkit.getPlayer(UUID.fromString(req.playerUuid));
        if (owner != null) {
            double safe = Math.max(config.getInt("subsidy-min", 20000),
                    Math.min(req.amount, config.getInt("subsidy-max", 40000)));
            int granted = grantGoods(owner, safe);
            gov.sendMessage(Text.color("&aApproved subsidy #" + id + " for &f" + req.playerName
                    + "&a (" + granted + " items granted)."));
            ctx.notifications().msg(owner, "&aYour subsidy request for '&f" + req.businessName
                    + "&a' was approved (goods worth " + Text.moneyPlain(safe) + ").");
        } else {
            gov.sendMessage(Text.color("&aApproved subsidy #" + id + ". &7Owner is offline - goods not granted yet."));
        }
        ctx.events().publish("government", Map.of("type", "subsidy", "business", req.businessName, "amount", req.amount));
    }

    public void denyRequest(Player gov, int id) {
        List<SubsidyRequest> list = requests();
        SubsidyRequest req = list.stream().filter(r -> r.id == id).findFirst().orElse(null);
        if (req == null) {
            gov.sendMessage(Text.color("&cNo subsidy request with id #" + id + "."));
            return;
        }
        req.status = "DENIED";
        saveRequests(list);
        gov.sendMessage(Text.color("&cDenied subsidy request #" + id + "."));
        Player owner = Bukkit.getPlayer(UUID.fromString(req.playerUuid));
        if (owner != null) {
            ctx.notifications().msg(owner, "&cYour subsidy request for '&f" + req.businessName + "&c' was denied.");
        }
    }

    private List<SubsidyRequest> requests() {
        List<SubsidyRequest> out = new ArrayList<>();
        ctx.storage().getModuleData("government", "requests").ifPresent(json -> {
            List<SubsidyRequest> loaded = Json.fromJson(json, REQUEST_LIST.getType());
            if (loaded != null) {
                out.addAll(loaded);
            }
        });
        return out;
    }

    private void saveRequests(List<SubsidyRequest> list) {
        ctx.storage().setModuleData("government", "requests", Json.toJson(list));
    }

    // --- /license ---------------------------------------------------------

    private void license(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (!c.player().hasPermission("vicemc.government.license")) {
            c.error("You are not authorized to manage licenses.");
            return;
        }
        switch (c.arg(0)) {
            case "issue" -> {
                if (c.size() < 2) {
                    c.usage("/license issue <player> <type>");
                    return;
                }
                UUID target = c.uuidArg(1);
                if (target == null) {
                    c.error("Player not found.");
                    return;
                }
                String type = c.arg(2).toUpperCase();
                business.manager().grantLicense(target, type);
                c.msg("&aIssued " + type + " license to " + c.arg(1) + ".");
                Player p = Bukkit.getPlayer(target);
                if (p != null) {
                    ctx.notifications().msg(p, "&aThe government issued you a " + type + " license.");
                }
            }
            case "revoke" -> {
                if (c.size() < 2) {
                    c.usage("/license revoke <player> <type>");
                    return;
                }
                UUID target = c.uuidArg(1);
                if (target == null) {
                    c.error("Player not found.");
                    return;
                }
                business.manager().revokeLicense(target, c.arg(2).toUpperCase());
                c.msg("&aRevoked " + c.arg(2).toUpperCase() + " license from " + c.arg(1) + ".");
            }
            case "list" -> {
                if (c.size() < 2) {
                    c.usage("/license list <player>");
                    return;
                }
                UUID target = c.uuidArg(1);
                if (target == null) {
                    c.error("Player not found.");
                    return;
                }
                c.msg("&6Licenses of &f" + c.arg(1) + "&6: &f" + String.join(", ", business.manager().licenses(target)));
            }
            default -> gui.openDashboard(c.player());
        }
    }

    // --- /gov -------------------------------------------------------------

    private void gov(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (!c.player().hasPermission("vicemc.government")) {
            c.error("You are not authorized to run government operations.");
            return;
        }
        switch (c.arg(0)) {
            case "subsidy" -> subsidy(c);
            case "seize" -> seize(c);
            case "businessban" -> businessban(c);
            case "pardon" -> pardon(c);
            case "flag" -> flag(c);
            case "requests" -> requests(c);
            case "serve" -> counterGui.openPicker(c.player());
            case "treasury" -> c.msg("&6Government treasury: &f" + Text.moneyPlain(treasury()));
            default -> gui.openDashboard(c.player());
        }
    }

    private void subsidy(CommandContext c) {
        if (c.size() < 3) {
            c.usage("/gov subsidy <businessId> <amount>");
            return;
        }
        Business target = business.manager().byId(c.argInt(1, -1)).orElse(null);
        if (target == null) {
            c.error("Business not found.");
            return;
        }
        double amount = Math.max(config.getInt("subsidy-min", 20000),
                Math.min(c.argDouble(2, 20000), config.getInt("subsidy-max", 40000)));
        Player owner = Bukkit.getPlayer(target.owner);
        if (owner == null) {
            c.error("Business owner is offline.");
            return;
        }
        int granted = grantGoods(owner, amount);
        c.msg("&aGranted a subsidy worth &f" + Text.moneyPlain(amount)
                + "&a (goods) to &f" + target.name + "&a (" + granted + " items).");
        ctx.notifications().msg(owner, "&aThe government approved a subsidy for your business '"
                + target.name + "' worth " + Text.moneyPlain(amount) + " in goods.");
        ctx.events().publish("government", Map.of("type", "subsidy", "business", target.name, "amount", amount));
    }

    private int grantGoods(Player player, double value) {
        ConfigurationSection goods = config.getSection("subsidy-goods");
        int totalItems = 0;
        for (String mat : goods.getKeys(false)) {
            Material material = Material.matchMaterial(mat);
            if (material == null) {
                continue;
            }
            double unitValue = goods.getDouble(mat);
            int count = (int) Math.floor(value / unitValue);
            if (count <= 0) {
                continue;
            }
            value -= count * unitValue;
            totalItems += count;
            int remaining = count;
            while (remaining > 0) {
                int batch = Math.min(remaining, 64);
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(material, batch));
                if (!leftover.isEmpty()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
                }
                remaining -= batch;
            }
        }
        return totalItems;
    }

    private void seize(CommandContext c) {
        if (c.size() < 3) {
            c.usage("/gov seize <player> <amount>");
            return;
        }
        UUID target = c.uuidArg(1);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        double amount = c.argDouble(2, -1);
        if (amount <= 0) {
            c.error("Invalid amount.");
            return;
        }
        var result = ctx.economy().withdraw(target, amount, "asset seizure");
        if (!result.success()) {
            c.error("Seizure failed: " + result.detail());
            return;
        }
        c.msg("&aSeized &f" + Text.moneyPlain(amount) + "&a from " + c.arg(1) + " for unpaid debts.");
        ctx.events().publish("government", Map.of("type", "seizure", "target", c.arg(1), "amount", amount));
    }

    private void businessban(CommandContext c) {
        if (c.size() < 2) {
            c.usage("/gov businessban <player> [reason]");
            return;
        }
        UUID target = c.uuidArg(1);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        business.manager().banFromBusiness(target, c.arg(2, "government action"));
        c.msg("&cBanned " + c.arg(1) + " from starting new businesses.");
    }

    private void pardon(CommandContext c) {
        if (c.size() < 2) {
            c.usage("/gov pardon <player>");
            return;
        }
        UUID target = c.uuidArg(1);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        business.manager().pardonBusiness(target);
        c.msg("&aPardoned " + c.arg(1) + " - they may start businesses again.");
    }

    private void flag(CommandContext c) {
        if (c.size() < 2) {
            c.usage("/gov flag <player> <reason>");
            return;
        }
        UUID target = c.uuidArg(1);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        business.manager().banFromBusiness(target, "abuse: " + c.arg(2, "no reason"));
        c.msg("&cFlagged " + c.arg(1) + " for abuse and banned from starting businesses (court review can overturn).");
        ctx.events().publish("government", Map.of("type", "businessban", "target", c.arg(1), "reason", c.arg(2)));
    }

    private void requests(CommandContext c) {
        if (c.size() < 2) {
            c.usage("/gov requests list | approve <id> | deny <id>");
            return;
        }
        switch (c.arg(1)) {
            case "list" -> listRequests(c.player());
            case "approve" -> {
                if (c.size() < 3) {
                    c.usage("/gov requests approve <id>");
                    return;
                }
                approveRequest(c.player(), c.argInt(2, -1));
            }
            case "deny" -> {
                if (c.size() < 3) {
                    c.usage("/gov requests deny <id>");
                    return;
                }
                denyRequest(c.player(), c.argInt(2, -1));
            }
            default -> c.msg("&6/gov requests list | approve <id> | deny <id>");
        }
    }

    private void govcounter(CommandContext c) {
        if (!c.isPlayer()) {
            c.error("Only players can use this.");
            return;
        }
        if (!c.player().hasPermission("vicemc.government")) {
            c.error("Only government employees can use the counter.");
            return;
        }
        counterGui.openPicker(c.player());
    }

    private List<String> playerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }
}
