package net.vicemc.modules.heists;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import net.vicemc.core.ViceCore;
import net.vicemc.modules.properties.PropertiesModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Family registration (150k + house) and coordinated heists with hostages,
 * getaway boats and island extraction.
 */
public final class HeistModule implements ViceModule {

    private ViceModuleContext ctx;
    private YamlConfig config;
    private FamilyManager families;
    private HeistManager heists;
    private HeistGui gui;
    private PropertiesModule properties;

    @Override
    public String id() {
        return "heists";
    }

    @Override
    public String displayName() {
        return "Vice Heists";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("heists.yml");
        this.families = new FamilyManager(ctx);
        this.heists = new HeistManager(ctx, config);
        this.gui = new HeistGui(ctx, this);
        this.properties = (PropertiesModule) ViceCore.get().getModuleRegistry().module("properties").orElse(null);
        if (properties == null) {
            ctx.logger().warning("Properties module not found; family house requirement is disabled.");
        }
        Bukkit.getPluginManager().registerEvents(heists, ctx.plugin());

        registerCommands();
        ctx.logger().info("Heist module ready.");
    }

    private void registerCommands() {
        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("family")
                .description("Mafia family management")
                .executes(this::family)
                .tabulates((c, a) -> a.size() <= 1
                        ? List.of("create", "invite", "join", "leave", "kick", "info", "members")
                        : playerNames())
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("heist")
                .description("Organize and run heists")
                .executes(this::heist)
                .tabulates((c, a) -> a.size() <= 1
                        ? List.of("list", "start", "abort", "return")
                        : a.get(0).equals("start") ? heistIds() : heists.helicopterRoutes())
                .build());
    }

    // --- GUI facade -------------------------------------------------------

    public ViceModuleContext context() {
        return ctx;
    }

    public YamlConfig heistConfig() {
        return config;
    }

    public FamilyManager families() {
        return families;
    }

    public HeistManager heists() {
        return heists;
    }

    public boolean createFamily(Player player, String name) {
        if (families.byOwner(player.getUniqueId()) != null || families.byMember(player.getUniqueId()) != null) {
            player.sendMessage(Text.color("&cYou are already in a family."));
            return false;
        }
        if (requiresHouse() && !ownsHouse(player)) {
            player.sendMessage(Text.color("&cFounding a family requires owning a home. Buy one with &e/property&c."));
            return false;
        }
        double fee = config.getDouble("registration-fee", 150000);
        var payment = ctx.economy().withdraw(player.getUniqueId(), fee, "family registration");
        if (!payment.success()) {
            player.sendMessage(Text.color("&cFamily registration costs " + Text.moneyPlain(fee) + "."));
            return false;
        }
        Family family = families.create(name, player.getUniqueId());
        player.sendMessage(Text.color("&aFamily '&f" + family.name + "&a' registered (ID " + family.id + "). "
                + "Invite members with /family invite <player>."));
        return true;
    }

    public void inviteToFamily(Player player, UUID target) {
        Family family = families.byOwner(player.getUniqueId());
        if (family == null) {
            player.sendMessage(Text.color("&cOnly a family owner can invite."));
            return;
        }
        families.invite(family.id, target);
        player.sendMessage(Text.color("&aInvited " + nameOf(target) + " to " + family.name + "."));
        Player invited = Bukkit.getPlayer(target);
        if (invited != null) {
            ctx.notifications().msg(invited, "&a" + player.getName() + " invited you to their family. "
                    + "Accept from the family menu.");
        }
    }

    public boolean joinFamily(Player player, int id) {
        if (families.byMember(player.getUniqueId()) != null) {
            player.sendMessage(Text.color("&cYou are already in a family."));
            return false;
        }
        Family family = families.byId(id).orElse(null);
        if (family == null) {
            player.sendMessage(Text.color("&cFamily not found."));
            return false;
        }
        if (!families.invites(id).contains(player.getUniqueId())) {
            player.sendMessage(Text.color("&cYou are not invited to this family."));
            return false;
        }
        families.join(id, player.getUniqueId());
        player.sendMessage(Text.color("&aYou joined the family &f" + family.name + "&a."));
        return true;
    }

    public void leaveFamily(Player player) {
        Family family = families.byMember(player.getUniqueId());
        if (family == null) {
            player.sendMessage(Text.color("&cYou are not in a family."));
            return;
        }
        boolean owner = family.owner.equals(player.getUniqueId());
        families.leave(player.getUniqueId());
        player.sendMessage(Text.color(owner
                ? "&cYou dissolved your family. Its members are now unaffiliated."
                : "&aYou left the family."));
    }

    public void kickMember(Player player, UUID target) {
        Family family = families.byOwner(player.getUniqueId());
        if (family == null) {
            player.sendMessage(Text.color("&cOnly a family owner can kick members."));
            return;
        }
        if (target == null || !family.members.contains(target)) {
            player.sendMessage(Text.color("&cMember not found."));
            return;
        }
        families.kick(family.id, target);
        player.sendMessage(Text.color("&aRemoved " + nameOf(target) + " from the family."));
    }

    public boolean startHeist(Player player, String siteId) {
        Family family = families.byMember(player.getUniqueId());
        if (family == null) {
            player.sendMessage(Text.color("&cYou must be in a family to start a heist."));
            return false;
        }
        if (heists.activeFor(player.getUniqueId()).isPresent()) {
            player.sendMessage(Text.color("&cYour crew is already in a heist."));
            return false;
        }
        if (heists.activeCount() >= config.getInt("heist.max-active", 3)) {
            player.sendMessage(Text.color("&cToo many heists are active right now."));
            return false;
        }
        HeistSite site = heists.site(siteId);
        if (site == null) {
            player.sendMessage(Text.color("&cUnknown site."));
            return false;
        }
        if (!ctx.regions().isInside(player.getLocation(), site.regionTag)) {
            player.sendMessage(Text.color("&cYou must be inside " + site.name + " to start this heist."));
            return false;
        }
        List<Player> crew = new java.util.ArrayList<>();
        for (UUID member : family.members) {
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                crew.add(online);
            }
        }
        if (crew.size() < 2) {
            player.sendMessage(Text.color("&cYou need at least two family members online to start a heist."));
            return false;
        }
        Heist heist = heists.start(player, site, crew);
        player.sendMessage(Text.color("&aHeist started at &f" + site.name + "&a! A hostage is being held. "
                + "Escape via the getaway boat."));
        return true;
    }

    public void abortHeist(Player player) {
        heists.abort(player.getUniqueId(), "the leader aborted the heist");
        player.sendMessage(Text.color("&cHeist aborted."));
    }

    public void returnFromIsland(Player player, String route) {
        if (!ctx.regions().isInside(player.getLocation(), config.getString("island-region", "heist:island"))) {
            player.sendMessage(Text.color("&cYou can only return from the island."));
            return;
        }
        heists.returnToWorld(player, route);
    }

    // --- /family ----------------------------------------------------------

    private void family(CommandContext c) {
        switch (c.arg(0)) {
            case "create" -> create(c);
            case "invite" -> invite(c);
            case "join" -> join(c);
            case "leave" -> leave(c);
            case "kick" -> kick(c);
            case "info" -> info(c);
            case "members" -> members(c);
            default -> {
                if (c.isPlayer()) {
                    gui.openDashboard(c.player());
                } else {
                    c.usage("/family create|invite|join|leave|kick|info|members");
                }
            }
        }
    }

    private void create(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (c.size() < 2) {
            c.usage("/family create <name>");
            return;
        }
        createFamily(c.player(), c.arg(1));
    }

    private boolean requiresHouse() {
        return config.getBoolean("family-requires-house", true);
    }

    private boolean ownsHouse(Player player) {
        return properties != null && properties.ownsAny(player.getUniqueId());
    }

    private void invite(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (c.size() < 2) {
            c.usage("/family invite <player>");
            return;
        }
        Player player = c.player();
        Family family = families.byOwner(player.getUniqueId());
        if (family == null) {
            c.error("Only a family owner can invite.");
            return;
        }
        Player target = c.playerArg(1);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        families.invite(family.id, target.getUniqueId());
        c.msg("&aInvited " + target.getName() + " to " + family.name + ".");
        ctx.notifications().msg(target, "&a" + player.getName() + " invited you to their family. "
                + "Use &e/family join " + family.id + "&a to accept.");
    }

    private void join(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (c.size() < 2) {
            c.usage("/family join <id>");
            return;
        }
        Player player = c.player();
        if (families.byMember(player.getUniqueId()) != null) {
            c.error("You are already in a family.");
            return;
        }
        int id = c.argInt(1, -1);
        Family family = families.byId(id).orElse(null);
        if (family == null) {
            c.error("Family not found.");
            return;
        }
        if (!families.invites(id).contains(player.getUniqueId())) {
            c.error("You are not invited to this family.");
            return;
        }
        families.join(id, player.getUniqueId());
        c.msg("&aYou joined the family &f" + family.name + "&a.");
    }

    private void leave(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        Player player = c.player();
        Family family = families.byMember(player.getUniqueId());
        if (family == null) {
            c.error("You are not in a family.");
            return;
        }
        boolean owner = family.owner.equals(player.getUniqueId());
        families.leave(player.getUniqueId());
        c.msg(owner
                ? "&cYou dissolved your family. Its members are now unaffiliated."
                : "&aYou left the family.");
    }

    private void kick(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (c.size() < 2) {
            c.usage("/family kick <member>");
            return;
        }
        Player player = c.player();
        Family family = families.byOwner(player.getUniqueId());
        if (family == null) {
            c.error("Only a family owner can kick members.");
            return;
        }
        UUID target = c.uuidArg(1);
        if (target == null || !family.members.contains(target)) {
            c.error("Member not found.");
            return;
        }
        families.kick(family.id, target);
        c.msg("&aRemoved " + c.arg(1) + " from the family.");
    }

    private void info(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        Family family = families.byMember(c.player().getUniqueId());
        if (family == null) {
            c.msg("&7You are not in a family. Register one with &e/family create <name>&7.");
            return;
        }
        c.msg("&6Family &f" + family.name + "&6 (ID " + family.id + ")");
        c.msg("  &7Owner: &f" + nameOf(family.owner));
        c.msg("  &7Members: &f" + family.members.size());
    }

    private void members(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        Family family = families.byMember(c.player().getUniqueId());
        if (family == null) {
            c.error("You are not in a family.");
            return;
        }
        c.msg("&6Members of " + family.name + "&6:");
        family.members.forEach(u -> c.msg("  &7- &f" + nameOf(u)));
    }

    // --- /heist -----------------------------------------------------------

    private void heist(CommandContext c) {
        switch (c.arg(0)) {
            case "list" -> list(c);
            case "start" -> start(c);
            case "abort" -> abort(c);
            case "return" -> returnToWorld(c);
            default -> {
                if (c.isPlayer()) {
                    gui.openSites(c.player(), 1);
                } else {
                    c.usage("/heist list|start|abort|return");
                }
            }
        }
    }

    private void list(CommandContext c) {
        c.msg("&6Heist sites (risk - payout - duration):");
        for (HeistSite site : heists.sites()) {
            c.msg("  &f" + site.id + " &7- &e" + site.name + "&7 (risk " + riskStars(site.risk)
                    + ", " + Text.moneyPlain(site.payout) + ", " + (site.durationSeconds / 60) + "min)");
        }
    }

    private void start(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (c.size() < 2) {
            c.usage("/heist start <site>");
            return;
        }
        Player player = c.player();
        Family family = families.byMember(player.getUniqueId());
        if (family == null) {
            c.error("You must be in a family to start a heist.");
            return;
        }
        if (heists.activeFor(player.getUniqueId()).isPresent()) {
            c.error("Your crew is already in a heist.");
            return;
        }
        if (heists.activeCount() >= config.getInt("heist.max-active", 3)) {
            c.error("Too many heists are active right now.");
            return;
        }
        HeistSite site = heists.site(c.arg(1));
        if (site == null) {
            c.error("Unknown site. Use /heist list.");
            return;
        }
        if (!ctx.regions().isInside(player.getLocation(), site.regionTag)) {
            c.error("You must be inside " + site.name + " to start this heist.");
            return;
        }
        List<Player> crew = new java.util.ArrayList<>();
        for (UUID member : family.members) {
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                crew.add(online);
            }
        }
        if (crew.size() < 2) {
            c.error("You need at least two family members online to start a heist.");
            return;
        }
        Heist heist = heists.start(player, site, crew);
        c.msg("&aHeist started at &f" + site.name + "&a! A hostage is being held. "
                + "Escape via the getaway boat and use &e/heist return&a.");
    }

    private void abort(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        heists.abort(c.player().getUniqueId(), "the leader aborted the heist");
        c.msg("&cHeist aborted.");
    }

    private void returnToWorld(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (c.size() < 2) {
            c.usage("/heist return <route>");
            return;
        }
        Player player = c.player();
        if (!ctx.regions().isInside(player.getLocation(), config.getString("island-region", "heist:island"))) {
            c.error("You can only return from the island.");
            return;
        }
        heists.returnToWorld(player, c.arg(1));
    }

    private String riskStars(int risk) {
        return "\u2605".repeat(risk) + "\u2606".repeat(Math.max(0, 3 - risk));
    }

    private List<String> heistIds() {
        return heists.sites().stream().map(s -> s.id).toList();
    }

    private List<String> playerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }
}
