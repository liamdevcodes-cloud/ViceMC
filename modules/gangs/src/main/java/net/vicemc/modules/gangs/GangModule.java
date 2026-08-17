package net.vicemc.modules.gangs;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-gang faction system. Admin-configurable territories, rewards, leaders,
 * capture places, and weekly holding payouts. Everything customizable from gangs.yml.
 */
public final class GangModule implements ViceModule, Listener {

    private static final NamespacedKey DRUG_KEY = NamespacedKey.fromString("vicemc:gang_drug");
    private static final String ADMIN_PERM = "vicemc.gangs.admin";

    private ViceModuleContext ctx;
    private YamlConfig config;
    private GangManager manager;
    private GangGui gui;
    private ScheduledTask electionTask;
    private ScheduledTask territoryTask;
    private ScheduledTask warSchedulerTask;
    private ScheduledTask weeklyRewardTask;
    private int weeklyDrugs = 5;
    private int captureTickSeconds = 5;
    private long lastOpenedWeek = -1;
    private long lastResolvedWeek = -1;
    private boolean adminWarToggle = false;
    private boolean warActive = false;
    private final Map<String, Set<UUID>> inside = new ConcurrentHashMap<>();
    private final Map<String, String> lastStatusKey = new ConcurrentHashMap<>();

    @Override public String id() { return "gangs"; }
    @Override public String displayName() { return "Vice Gangs"; }
    @Override public String version() { return "3.0.0"; }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("gangs.yml");
        this.manager = new GangManager(ctx);
        this.gui = new GangGui(ctx, manager, this);
        this.weeklyDrugs = config.getInt("weekly-drugs", 5);
        this.captureTickSeconds = Math.max(1, config.getInt("capture.tick-seconds", 5));

        Bukkit.getPluginManager().registerEvents(this, ctx.plugin());

        long open = ctx.storage().getModuleData("gangs", "election:openWeek").map(Long::parseLong).orElse(-1L);
        long resolved = ctx.storage().getModuleData("gangs", "election:resolvedWeek").map(Long::parseLong).orElse(-1L);
        if (open != -1) lastOpenedWeek = open;
        if (resolved != -1) lastResolvedWeek = resolved;

        registerCommands();
        scheduleElections();
        scheduleTerritoryTick();
        scheduleWarWindow();
        scheduleWeeklyRewards();
        warnMissingRegions();
        ctx.logger().info("Gang module v3.0 ready (admin-configurable).");
    }

    private void warnMissingRegions() {
        for (Territory t : manager.territories()) {
            if (ctx.regions().byTag(t.regionTag).isEmpty()) {
                ctx.logger().warning("Territory '" + t.id + "' has no region tagged '"
                        + t.regionTag + "'. Use /gang admin setregion " + t.id + " <tag> or create one with /vregion.");
            }
        }
    }

    @Override
    public void onDisable() {
        cancelQuietly(electionTask);
        cancelQuietly(territoryTask);
        cancelQuietly(warSchedulerTask);
        cancelQuietly(weeklyRewardTask);
        ctx.storage().setModuleData("gangs", "election:openWeek", String.valueOf(lastOpenedWeek));
        ctx.storage().setModuleData("gangs", "election:resolvedWeek", String.valueOf(lastResolvedWeek));
    }

    // ========================= COMMANDS =========================

    private void registerCommands() {
        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("gang")
                .description("Gang system - membership, territories, admin tools")
                .executes(this::gang)
                .tabulates((c, a) -> {
                    if (a.size() <= 1) return List.of("join", "leave", "info", "members", "vote", "drugs", "territories", "kick", "bank", "promote", "demote", "admin");
                    if (a.get(0).equals("admin")) {
                        if (a.size() <= 2) return List.of("setleader", "setlieutenant", "setmember", "forcejoin", "forceleave", "createterritory", "deleteterritory", "setregion", "setcapturetime", "setrewardmoney", "setweeklyreward", "addrewarditem", "clearrewarditems", "togglewar", "reload");
                        if (a.get(1).equals("setleader") || a.get(1).equals("setlieutenant") || a.get(1).equals("setmember") || a.get(1).equals("forcejoin") || a.get(1).equals("forceleave"))
                            return playerNames();
                        if (a.get(1).equals("createterritory"))
                            return List.of("<id>");
                        if (a.get(1).equals("deleteterritory") || a.get(1).equals("setregion") || a.get(1).equals("setcapturetime") || a.get(1).equals("setrewardmoney") || a.get(1).equals("setweeklyreward") || a.get(1).equals("clearrewarditems") || a.get(1).equals("addrewarditem"))
                            return territoryIds();
                        if (a.get(1).equals("addrewarditem") && a.size() <= 4)
                            return List.of("MATERIAL");
                        if (a.get(1).equals("setleader") || a.get(1).equals("setlieutenant"))
                            return List.of("north", "south");
                    }
                    if (a.get(0).equals("join")) return List.of("north", "south");
                    if (a.get(0).equals("kick") || a.get(0).equals("promote") || a.get(0).equals("demote")) return playerNames();
                    return List.of();
                })
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("vote")
                .description("Cast your biweekly gang vote")
                .executes(this::vote)
                .tabulates((c, a) -> a.size() <= 1 ? gangMemberNames(c) : List.of())
                .build());
    }

    // ========================= /gang DISPATCH =========================

    private void gang(CommandContext c) {
        switch (c.arg(0)) {
            case "join" -> join(c);
            case "leave" -> { if (c.isPlayer()) leave(c); }
            case "info" -> { if (c.isPlayer()) info(c); }
            case "members" -> { if (c.isPlayer()) members(c); }
            case "vote" -> vote(c);
            case "drugs" -> { if (c.isPlayer()) drugs(c); }
            case "territories" -> { if (c.isPlayer()) gui.openTerritories(c.player()); }
            case "kick" -> { if (c.isPlayer()) kick(c); }
            case "bank" -> { if (c.isPlayer()) gui.openBank(c.player()); }
            case "promote" -> { if (c.isPlayer()) promote(c); }
            case "demote" -> { if (c.isPlayer()) demote(c); }
            case "admin" -> adminCmd(c);
            default -> { if (c.isPlayer()) gui.openDashboard(c.player()); }
        }
    }

    // ========================= ADMIN COMMANDS =========================

    private boolean requireAdmin(CommandContext c) {
        if (c.sender().hasPermission(ADMIN_PERM)) return true;
        c.error("No permission. Need " + ADMIN_PERM);
        return false;
    }

    private void adminCmd(CommandContext c) {
        if (!requireAdmin(c)) return;
        String sub = c.arg(1);
        switch (sub) {
            case "setleader" -> adminSetLeader(c);
            case "setlieutenant" -> adminSetLieutenant(c);
            case "setmember" -> adminSetMember(c);
            case "forcejoin" -> adminForceJoin(c);
            case "forceleave" -> adminForceLeave(c);
            case "createterritory" -> adminCreateTerritory(c);
            case "deleteterritory" -> adminDeleteTerritory(c);
            case "setregion" -> adminSetRegion(c);
            case "setcapturetime" -> adminSetCaptureTime(c);
            case "setrewardmoney" -> adminSetRewardMoney(c);
            case "setweeklyreward" -> adminSetWeeklyReward(c);
            case "addrewarditem" -> adminAddRewardItem(c);
            case "clearrewarditems" -> adminClearRewardItems(c);
            case "togglewar" -> { if (c.isPlayer()) toggleWar(c.player()); }
            case "reload" -> adminReload(c);
            default -> c.msg("&6Admin commands:\n"
                    + "  &e/gang admin setleader <player> <north|south> &7- Assign gang leader\n"
                    + "  &e/gang admin setlieutenant <player> <north|south> &7- Assign lieutenant\n"
                    + "  &e/gang admin setmember <player> <north|south> &7- Force member into gang\n"
                    + "  &e/gang admin forcejoin <player> <north|south> &7- Join bypassing balance\n"
                    + "  &e/gang admin forceleave <player> &7- Remove from gang\n"
                    + "  &e/gang admin createterritory <id> &7- Create capture territory\n"
                    + "  &e/gang admin deleteterritory <id> &7- Remove territory\n"
                    + "  &e/gang admin setregion <territory> <regionTag> &7- Set region\n"
                    + "  &e/gang admin setcapturetime <territory> <seconds> &7- Set capture time\n"
                    + "  &e/gang admin setrewardmoney <territory> <amount> &7- Set capture reward\n"
                    + "  &e/gang admin setweeklyreward <territory> <amount> &7- Set weekly holding reward\n"
                    + "  &e/gang admin addrewarditem <territory> <MATERIAL> [amount] &7- Add reward item\n"
                    + "  &e/gang admin clearrewarditems <territory> &7- Clear reward items\n"
                    + "  &e/gang admin togglewar &7- Toggle territory war\n"
                    + "  &e/gang admin reload &7- Reload config");
        }
    }

    private void adminSetLeader(CommandContext c) {
        if (c.size() < 3) { c.usage("/gang admin setleader <player> <north|south>"); return; }
        Player target = c.playerArg(1);
        if (target == null) { c.error("Player not found."); return; }
        String gangId = c.arg(2).toLowerCase();
        if (manager.gang(gangId) == null) { c.error("Invalid gang. Use: north, south"); return; }
        if (!manager.isMember(target.getUniqueId(), gangId)) {
            manager.adminForceJoin(target.getUniqueId(), gangId);
        }
        manager.setLeader(gangId, target.getUniqueId());
        c.msg("&aSet &f" + target.getName() + " &as leader of &f" + manager.gang(gangId).name);
        ctx.notifications().msg(target, "&6You have been appointed gang leader by an admin!");
    }

    private void adminSetLieutenant(CommandContext c) {
        if (c.size() < 3) { c.usage("/gang admin setlieutenant <player> <north|south>"); return; }
        Player target = c.playerArg(1);
        if (target == null) { c.error("Player not found."); return; }
        String gangId = c.arg(2).toLowerCase();
        if (manager.gang(gangId) == null) { c.error("Invalid gang. Use: north, south"); return; }
        if (!manager.isMember(target.getUniqueId(), gangId)) {
            manager.adminForceJoin(target.getUniqueId(), gangId);
        }
        manager.promoteToLieutenant(gangId, target.getUniqueId());
        c.msg("&aSet &f" + target.getName() + " &as lieutenant of &f" + manager.gang(gangId).name);
        ctx.notifications().msg(target, "&6You have been appointed lieutenant by an admin!");
    }

    private void adminSetMember(CommandContext c) {
        if (c.size() < 3) { c.usage("/gang admin setmember <player> <north|south>"); return; }
        Player target = c.playerArg(1);
        if (target == null) { c.error("Player not found."); return; }
        String gangId = c.arg(2).toLowerCase();
        if (manager.gang(gangId) == null) { c.error("Invalid gang. Use: north, south"); return; }
        manager.adminForceJoin(target.getUniqueId(), gangId);
        c.msg("&aForce-placed &f" + target.getName() + " &ainto &f" + manager.gang(gangId).name);
    }

    private void adminForceJoin(CommandContext c) {
        if (c.size() < 3) { c.usage("/gang admin forcejoin <player> <north|south>"); return; }
        Player target = c.playerArg(1);
        if (target == null) { c.error("Player not found."); return; }
        String gangId = c.arg(2).toLowerCase();
        if (manager.gang(gangId) == null) { c.error("Invalid gang. Use: north, south"); return; }
        manager.adminForceJoin(target.getUniqueId(), gangId);
        c.msg("&aForce-joined &f" + target.getName() + " &ainto &f" + manager.gang(gangId).name + " &7(bypassing balance)");
    }

    private void adminForceLeave(CommandContext c) {
        if (c.size() < 2) { c.usage("/gang admin forceleave <player>"); return; }
        Player target = c.playerArg(1);
        if (target == null) { c.error("Player not found."); return; }
        if (manager.adminForceLeave(target.getUniqueId())) {
            c.msg("&aRemoved &f" + target.getName() + " &afrom their gang.");
        } else {
            c.error(target.getName() + " is not in any gang.");
        }
    }

    private void adminCreateTerritory(CommandContext c) {
        if (c.size() < 2) { c.usage("/gang admin createterritory <id> [name] [captureSeconds] [rewardMoney] [weeklyReward]"); return; }
        String id = c.arg(1).toLowerCase();
        if (manager.territory(id) != null) { c.error("Territory '" + id + "' already exists."); return; }
        String name = c.arg(2, "&e" + id);
        int capture = c.argInt(3, 60);
        double reward = c.argDouble(4, 0);
        double weekly = c.argDouble(5, 0);
        Territory t = manager.createTerritory(id, name, "gangzone:" + id, capture, reward, weekly);
        if (t != null) {
            c.msg("&aCreated territory '&f" + id + "&a' - use &e/gang admin setregion " + id + " <regionTag> &ato assign a region.");
        } else {
            c.error("Could not create territory.");
        }
    }

    private void adminDeleteTerritory(CommandContext c) {
        if (c.size() < 2) { c.usage("/gang admin deleteterritory <id>"); return; }
        String id = c.arg(1).toLowerCase();
        if (manager.deleteTerritory(id)) {
            c.msg("&aDeleted territory '&f" + id + "&a'.");
        } else {
            c.error("Territory '" + id + "' not found.");
        }
    }

    private void adminSetRegion(CommandContext c) {
        if (c.size() < 3) { c.usage("/gang admin setregion <territoryId> <regionTag>"); return; }
        String id = c.arg(1).toLowerCase();
        String tag = c.arg(2);
        if (manager.setTerritoryRegion(id, tag)) {
            c.msg("&aSet region tag for &f" + id + " &ato &f" + tag);
        } else {
            c.error("Territory '" + id + "' not found.");
        }
    }

    private void adminSetCaptureTime(CommandContext c) {
        if (c.size() < 3) { c.usage("/gang admin setcapturetime <territoryId> <seconds>"); return; }
        String id = c.arg(1).toLowerCase();
        int seconds = c.argInt(2, 60);
        if (manager.setTerritoryCaptureTime(id, seconds)) {
            c.msg("&aSet capture time for &f" + id + " &ato &f" + seconds + "s");
        } else {
            c.error("Territory '" + id + "' not found.");
        }
    }

    private void adminSetRewardMoney(CommandContext c) {
        if (c.size() < 3) { c.usage("/gang admin setrewardmoney <territoryId> <amount>"); return; }
        String id = c.arg(1).toLowerCase();
        double amount = c.argDouble(2, 0);
        if (manager.setTerritoryRewardMoney(id, amount)) {
            c.msg("&aSet capture reward for &f" + id + " &ato &a$" + String.format("%.0f", amount));
        } else {
            c.error("Territory '" + id + "' not found.");
        }
    }

    private void adminSetWeeklyReward(CommandContext c) {
        if (c.size() < 3) { c.usage("/gang admin setweeklyreward <territoryId> <amount>"); return; }
        String id = c.arg(1).toLowerCase();
        double amount = c.argDouble(2, 0);
        if (manager.setTerritoryWeeklyReward(id, amount)) {
            c.msg("&aSet weekly holding reward for &f" + id + " &ato &a$" + String.format("%.0f", amount));
        } else {
            c.error("Territory '" + id + "' not found.");
        }
    }

    private void adminAddRewardItem(CommandContext c) {
        if (c.size() < 3) { c.usage("/gang admin addrewarditem <territoryId> <MATERIAL> [amount]"); return; }
        String id = c.arg(1).toLowerCase();
        Material mat = Material.matchMaterial(c.arg(2).toUpperCase());
        if (mat == null) { c.error("Invalid material: " + c.arg(2)); return; }
        int amount = c.argInt(3, 1);
        ItemStack item = ItemBuilder.of(mat).name("&c" + mat.name()).build();
        item.setAmount(amount);
        if (manager.addTerritoryRewardItem(id, item)) {
            c.msg("&aAdded &f" + amount + "x " + mat.name() + " &areward to territory &f" + id);
        } else {
            c.error("Territory '" + id + "' not found.");
        }
    }

    private void adminClearRewardItems(CommandContext c) {
        if (c.size() < 2) { c.usage("/gang admin clearrewarditems <territoryId>"); return; }
        String id = c.arg(1).toLowerCase();
        if (manager.clearTerritoryRewardItems(id)) {
            c.msg("&aCleared reward items for &f" + id);
        } else {
            c.error("Territory '" + id + "' not found.");
        }
    }

    private void adminReload(CommandContext c) {
        this.config = ctx.yaml("gangs.yml");
        this.weeklyDrugs = config.getInt("weekly-drugs", 5);
        this.captureTickSeconds = Math.max(1, config.getInt("capture.tick-seconds", 5));
        manager.reload();
        gui.reload();
        c.msg("&aGangs config reloaded.");
    }

    // ========================= PLAYER COMMANDS =========================

    private void join(CommandContext c) {
        if (!c.isPlayer()) return;
        if (c.size() < 2) { c.usage("/gang join <north|south>"); return; }
        Player player = c.player();
        if (manager.gangOf(player.getUniqueId()) != null) { c.error("Already in a gang. /gang leave first."); return; }
        String gangId = c.arg(1).toLowerCase();
        Gang gang = manager.gang(gangId);
        if (gang == null) { c.error("Invalid gang. Use: north, south"); return; }
        if (!manager.canJoin(gangId)) {
            c.error("That gang has too many more members (max diff: 4). Wait for balance.");
            return;
        }
        if (manager.join(player.getUniqueId(), gangId)) {
            c.msg("&aYou joined &f" + gang.name + "&a.");
            ctx.events().publish("gangs.election", Map.of("type", "join", "gang", gangId, "player", player.getName()));
        }
    }

    private void leave(CommandContext c) {
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) { c.error("Not in a gang."); return; }
        manager.leave(player.getUniqueId());
        c.msg("&aYou left your gang.");
    }

    private void kick(CommandContext c) {
        if (!c.isPlayer() || c.size() < 2) { if (c.isPlayer()) c.usage("/gang kick <player>"); return; }
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) { c.error("Not in a gang."); return; }
        if (!manager.isHigherRank(player.getUniqueId(), gangId)) { c.error("Only leader/lieutenants can kick."); return; }
        Player target = c.playerArg(1);
        if (target == null || !manager.isMember(target.getUniqueId(), gangId)) { c.error("Player not found or not in your gang."); return; }
        if (target.getUniqueId().equals(player.getUniqueId())) { c.error("Can't kick yourself."); return; }
        manager.leave(target.getUniqueId());
        c.msg("&aKicked &f" + target.getName());
        ctx.notifications().msg(target, "&cYou were kicked from your gang.");
    }

    private void promote(CommandContext c) {
        if (!c.isPlayer() || c.size() < 2) { if (c.isPlayer()) c.usage("/gang promote <player>"); return; }
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) { c.error("Not in a gang."); return; }
        if (!manager.isLeader(player.getUniqueId(), gangId)) { c.error("Only the leader can promote."); return; }
        Player target = c.playerArg(1);
        if (target == null || !manager.isMember(target.getUniqueId(), gangId)) { c.error("Player not found or not in your gang."); return; }
        manager.promoteToLieutenant(gangId, target.getUniqueId());
        c.msg("&aPromoted &f" + target.getName() + " &ato lieutenant.");
        ctx.notifications().msg(target, "&6You've been promoted to lieutenant!");
    }

    private void demote(CommandContext c) {
        if (!c.isPlayer() || c.size() < 2) { if (c.isPlayer()) c.usage("/gang demote <player>"); return; }
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) { c.error("Not in a gang."); return; }
        if (!manager.isLeader(player.getUniqueId(), gangId)) { c.error("Only the leader can demote."); return; }
        Player target = c.playerArg(1);
        if (target == null) { c.error("Player not found."); return; }
        if (!manager.isLieutenant(target.getUniqueId(), gangId)) { c.error(target.getName() + " is not a lieutenant."); return; }
        manager.demoteFromLieutenant(gangId, target.getUniqueId());
        c.msg("&aDemoted &f" + target.getName());
        ctx.notifications().msg(target, "&cYou've been demoted from lieutenant.");
    }

    private void info(CommandContext c) {
        if (!c.isPlayer()) return;
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) { c.msg("&7Not in a gang. &e/gang join"); return; }
        Gang gang = manager.gang(gangId);
        if (gang == null) { manager.leave(player.getUniqueId()); c.msg("&7Gang no longer exists."); return; }
        UUID leader = manager.leaderOf(gangId);
        c.msg("&6" + gang.name);
        c.msg("  &7Leader: &e" + (leader != null ? nameOf(leader) : "&7none"));
        c.msg("  &7Lieutenants: &f" + manager.lieutenantsOf(gangId).size());
        c.msg("  &7Members: &f" + manager.memberCount(gangId));
        c.msg("  &7Territories: &f" + manager.territoriesControlled(gangId) + " &7of &f" + manager.territories().size());
        c.msg("  &7Bank: &a$" + String.format("%.2f", manager.bankBalance(gangId)));
        c.msg("  &7War: " + (warActive ? "&aActive" : "&7Inactive"));
    }

    private void members(CommandContext c) {
        if (!c.isPlayer()) return;
        String gangId = manager.gangOf(c.player().getUniqueId());
        if (gangId == null) { c.error("Not in a gang."); return; }
        Gang gang = manager.gang(gangId);
        if (gang == null) { manager.leave(c.player().getUniqueId()); c.error("Gang no longer exists."); return; }
        c.msg("&6" + gang.name + " Members:");
        for (UUID u : manager.membersOf(gangId)) {
            String rank = manager.rankName(u, gangId);
            String p = manager.isLeader(u, gangId) ? "&6" : manager.isLieutenant(u, gangId) ? "&e" : "&7";
            c.msg("  " + p + nameOf(u) + " &7- " + rank + " &7(kills: &f" + manager.killsAllTime(gangId, u) + "&7)");
        }
    }

    private void vote(CommandContext c) {
        if (!c.isPlayer()) return;
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) { c.error("Not in a gang."); return; }
        if (manager.gang(gangId) == null) { manager.leave(player.getUniqueId()); c.error("Gang no longer exists."); return; }
        if (!electionOpen()) { c.error("Elections not open. Runs biweekly on " + config.getString("election.day", "SATURDAY") + "."); return; }
        if (c.size() < 2) { gui.openVote(player, gangId, 1); return; }
        Player candidate = c.playerArg(1);
        if (candidate == null || !manager.isMember(candidate.getUniqueId(), gangId)) { c.error("Candidate must be in your gang."); return; }
        manager.vote(gangId, player.getUniqueId(), candidate.getUniqueId());
        int v = manager.voteCount(gangId, candidate.getUniqueId());
        ctx.notifications().msg(player, "&aVoted for &f" + candidate.getName() + " &a(" + v + " total)");
        ctx.notifications().msg(candidate, "&eVote from " + player.getName() + " (" + v + " total)");
    }

    private void drugs(CommandContext c) {
        if (!c.isPlayer()) return;
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) { c.error("Not in a gang."); return; }
        if (!manager.controlsAny(gangId)) { c.error("Capture a territory first."); return; }
        Gang gang = manager.gang(gangId);
        int claim = manager.claimDrug(player.getUniqueId(), weeklyDrugs);
        if (claim == -1) { c.error("Already claimed this week."); return; }
        Material mat = Material.matchMaterial(gang.drugMaterial);
        if (mat == null) mat = Material.SUGAR;
        ItemStack drug = ItemBuilder.of(mat).name(gang.drugName)
                .lore("&7Gang booster: &f" + gang.drugEffect + " " + gang.drugAmplifier, "&7Claimed " + claim + "/" + weeklyDrugs)
                .tag(DRUG_KEY, gangId).build();
        var leftover = player.getInventory().addItem(drug);
        if (!leftover.isEmpty()) player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
        c.msg("&aClaimed &f" + gang.drugName + "&a (" + claim + "/" + weeklyDrugs + ")");
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        String gangId = ItemBuilder.tag(event.getItem(), DRUG_KEY);
        if (gangId == null) return;
        Gang gang = manager.gang(gangId);
        if (gang == null) return;
        PotionEffectType effect = PotionEffectType.getByName(gang.drugEffect);
        if (effect == null) effect = PotionEffectType.HASTE;
        event.getPlayer().addPotionEffect(new PotionEffect(effect, gang.drugDurationSeconds * 20, Math.max(0, gang.drugAmplifier - 1), false, false, true));
        ctx.notifications().msg(event.getPlayer(), "&8" + gang.drugName + " kicking in...");
    }

    // ========================= WAR SCHEDULING =========================

    private void scheduleWarWindow() {
        warSchedulerTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(), t -> checkWarWindow(), 60L, 600L);
    }

    private void checkWarWindow() {
        ZoneId zone = ZoneId.of(config.getString("war.timezone", "Europe/Berlin"));
        LocalDateTime now = LocalDateTime.now(zone);
        DayOfWeek warStart = getDayConfig("war.start-day", DayOfWeek.FRIDAY);
        DayOfWeek warEnd = getDayConfig("war.end-day", DayOfWeek.SUNDAY);
        int startH = config.getInt("war.start-hour", 20);
        int endH = config.getInt("war.end-hour", 23);
        boolean inWindow = isWithinDayRange(now.getDayOfWeek(), warStart, warEnd) && now.getHour() >= startH && now.getHour() < endH;
        boolean active = inWindow || adminWarToggle;

        if (active && !warActive) {
            warActive = true;
            manager.setWarActive(true);
            ctx.notifications().broadcast("&6&lTERRITORY WAR has begun! Capture territories for rewards!");
            ctx.events().publish("gangs.war", Map.of("active", "true"));
        } else if (!active && warActive && !adminWarToggle) {
            warActive = false;
            manager.setWarActive(false);
            ctx.notifications().broadcast("&7Territory war ended.");
            ctx.events().publish("gangs.war", Map.of("active", "false"));
        }
    }

    private boolean isWithinDayRange(DayOfWeek current, DayOfWeek start, DayOfWeek end) {
        int c = current.getValue(), s = start.getValue(), e = end.getValue();
        return s <= e ? (c >= s && c <= e) : (c >= s || c <= e);
    }

    private DayOfWeek getDayConfig(String key, DayOfWeek def) {
        try { return DayOfWeek.valueOf(config.getString(key, def.name()).toUpperCase()); }
        catch (IllegalArgumentException ex) { return def; }
    }

    public void toggleWar(Player admin) {
        adminWarToggle = !adminWarToggle;
        if (adminWarToggle) {
            warActive = true;
            manager.setWarActive(true);
            ctx.notifications().broadcast("&6&lTERRITORY WAR toggled ON by admin!");
        } else if (!isScheduledWarActive()) {
            warActive = false;
            manager.setWarActive(false);
            ctx.notifications().broadcast("&7Territory war toggled OFF.");
        } else {
            ctx.notifications().msg(admin, "&7Still within scheduled window.");
            adminWarToggle = true;
        }
    }

    private boolean isScheduledWarActive() {
        ZoneId zone = ZoneId.of(config.getString("war.timezone", "Europe/Berlin"));
        LocalDateTime now = LocalDateTime.now(zone);
        DayOfWeek s = getDayConfig("war.start-day", DayOfWeek.FRIDAY);
        DayOfWeek e = getDayConfig("war.end-day", DayOfWeek.SUNDAY);
        return isWithinDayRange(now.getDayOfWeek(), s, e) && now.getHour() >= config.getInt("war.start-hour", 20) && now.getHour() < config.getInt("war.end-hour", 23);
    }

    public boolean isWarActive() { return warActive; }

    // ========================= WEEKLY REWARDS =========================

    private void scheduleWeeklyRewards() {
        weeklyRewardTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(), t -> distributeWeeklyRewards(), 1200L, 72000L);
    }

    private void distributeWeeklyRewards() {
        Map<String, Double> rewards = manager.distributeWeeklyRewards();
        if (rewards.isEmpty()) return;
        StringBuilder msg = new StringBuilder("&6Weekly territory income deposited to gang banks:");
        rewards.forEach((gangId, amount) -> {
            Gang g = manager.gang(gangId);
            String name = g == null ? gangId : g.name;
            msg.append("\n  &f").append(name).append(" &a+").append(String.format("%.0f", amount)).append("$");
        });
        ctx.notifications().broadcast(msg.toString());
    }

    // ========================= ELECTION SCHEDULING =========================

    private void scheduleElections() {
        electionTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(), t -> tickElections(), 60L, 600L);
    }

    private void tickElections() {
        DayOfWeek day = getDayConfig("election.day", DayOfWeek.SATURDAY);
        int hour = config.getInt("election.hour", 12);
        int window = config.getInt("election.window-hours", 24);
        int every = config.getInt("election.every-weeks", 2);
        LocalDate today = LocalDate.now();
        long week = today.toEpochDay() / 7;
        if ((week % every) != 0) { lastOpenedWeek = -1; return; }

        LocalDateTime start = today.with(TemporalAdjusters.previousOrSame(day)).atTime(hour, 0);
        LocalDateTime end = start.plusHours(window);
        LocalDateTime now = LocalDateTime.now();

        if (!now.isBefore(start) && now.isBefore(end) && lastOpenedWeek != week) {
            lastOpenedWeek = week;
            ctx.storage().setModuleData("gangs", "election:openWeek", String.valueOf(week));
            ctx.notifications().broadcast("&6Biweekly gang election OPEN! Use &e/vote");
            ctx.events().publish("gangs.election", Map.of("type", "open", "week", String.valueOf(week)));
        } else if (!now.isBefore(end) && lastResolvedWeek != week) {
            lastResolvedWeek = week;
            ctx.storage().setModuleData("gangs", "election:resolvedWeek", String.valueOf(week));
            resolveElections(week);
        }
    }

    private void resolveElections(long week) {
        StringBuilder results = new StringBuilder("&6Election closed! New leaders:");
        for (Gang gang : manager.all()) {
            UUID winner = manager.countWinner(gang.id);
            manager.clearVotes();
            if (winner != null) {
                manager.setLeader(gang.id, winner);
                results.append("\n  &f").append(gang.name).append(" &7-> &e").append(nameOf(winner));
            } else {
                results.append("\n  &7").append(gang.name).append(" &7-> no votes");
            }
        }
        manager.resetTermKills();
        ctx.notifications().broadcast(results.toString());
    }

    public boolean electionOpen() {
        DayOfWeek day = getDayConfig("election.day", DayOfWeek.SATURDAY);
        int hour = config.getInt("election.hour", 12);
        int window = config.getInt("election.window-hours", 24);
        int every = config.getInt("election.every-weeks", 2);
        LocalDate today = LocalDate.now();
        long week = today.toEpochDay() / 7;
        if ((week % every) != 0) return false;
        LocalDateTime start = today.with(TemporalAdjusters.previousOrSame(day)).atTime(hour, 0);
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(start) && now.isBefore(start.plusHours(window));
    }

    // ========================= TERRITORY CAPTURE =========================

    private void scheduleTerritoryTick() {
        territoryTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(), t -> tickTerritories(), 60L, captureTickSeconds * 20L);
    }

    private void tickTerritories() {
        for (Territory t : manager.territories()) {
            Map<String, Integer> countByGang = new HashMap<>();
            List<Player> insidePlayers = new ArrayList<>();
            Set<UUID> uuids = inside.getOrDefault(t.id, Set.of());
            for (UUID uuid : uuids) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) { inside.get(t.id).remove(uuid); continue; }
                insidePlayers.add(p);
                String g = manager.gangOf(uuid);
                if (g != null) countByGang.merge(g, 1, Integer::sum);
            }
            int rate = Math.max(1, (int) Math.ceil(100.0 * captureTickSeconds / t.captureSeconds));
            if (insidePlayers.isEmpty()) {
                if (t.progress > 0) manager.updateProgress(t.id, t.progress - rate, "");
                notifyTerritoryStatus(t, insidePlayers);
                continue;
            }
            if (t.owner.isEmpty()) {
                Map.Entry<String, Integer> top = null;
                boolean tie = false;
                for (var e : countByGang.entrySet()) {
                    if (top == null || e.getValue() > top.getValue()) { top = e; tie = false; }
                    else if (e.getValue() == top.getValue()) tie = true;
                }
                if (top == null || tie) {
                    if (t.progress > 0) manager.updateProgress(t.id, t.progress - rate, "");
                } else {
                    int next = t.progressOwner.equals(top.getKey()) ? t.progress + rate : rate;
                    if (next >= 100) capture(t, top.getKey());
                    else manager.updateProgress(t.id, next, top.getKey());
                }
            } else {
                String attGang = "";
                int atk = 0;
                for (var e : countByGang.entrySet()) {
                    if (!e.getKey().equals(t.owner) && e.getValue() > atk) { atk = e.getValue(); attGang = e.getKey(); }
                }
                int def = countByGang.getOrDefault(t.owner, 0);
                if (atk > 0 && atk > def) {
                    int next = t.progressOwner.equals(attGang) ? t.progress + rate : rate;
                    if (next >= 100) capture(t, attGang);
                    else manager.updateProgress(t.id, next, attGang);
                } else if (t.progress > 0) {
                    manager.updateProgress(t.id, t.progress - rate, "");
                }
            }
            notifyTerritoryStatus(t, insidePlayers);
        }
    }

    private void capture(Territory t, String gangId) {
        manager.captureTerritory(t.id, gangId);
        Gang g = manager.gang(gangId);
        String name = g == null ? gangId : g.name;
        ctx.notifications().broadcast("&6" + t.name + " &7captured by &f" + name + "&7!");
        if (warActive) distributeRewards(t, gangId);
        ctx.events().publish("gangs.territory", Map.of("zone", t.id, "gang", gangId, "war", String.valueOf(warActive)));
    }

    private void distributeRewards(Territory t, String gangId) {
        if (t.rewardMoney > 0) {
            manager.bankDeposit(gangId, t.rewardMoney);
            Gang g = manager.gang(gangId);
            ctx.notifications().broadcast("&a+$" + String.format("%.0f", t.rewardMoney) + " -> " + (g != null ? g.name : gangId) + " bank");
        }
        for (ItemStack rewardItem : t.rewardItems) {
            for (UUID uuid : manager.membersOf(gangId)) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    var leftover = p.getInventory().addItem(rewardItem.clone());
                    if (!leftover.isEmpty()) leftover.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
                    ctx.notifications().msg(p, "&6Reward from " + t.name + "!");
                }
            }
        }
    }

    private void notifyTerritoryStatus(Territory t, List<Player> insidePlayers) {
        String line = statusLine(t);
        String key = t.owner + "|" + t.progressOwner + "|" + (t.progress / 10);
        if (key.equals(lastStatusKey.get(t.id))) return;
        lastStatusKey.put(t.id, key);
        for (Player p : insidePlayers) ctx.notifications().action(p, line);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock()) return;
        UUID uuid = e.getPlayer().getUniqueId();
        for (Territory t : manager.territories()) {
            boolean was = ctx.regions().isInside(e.getFrom(), t.regionTag);
            boolean now = ctx.regions().isInside(e.getTo(), t.regionTag);
            if (was == now) continue;
            Set<UUID> set = inside.computeIfAbsent(t.id, k -> java.util.Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>()));
            if (now) set.add(uuid); else set.remove(uuid);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Location loc = e.getPlayer().getLocation();
        for (Territory t : manager.territories()) {
            if (ctx.regions().isInside(loc, t.regionTag))
                inside.computeIfAbsent(t.id, k -> java.util.Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>())).add(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        for (Set<UUID> set : inside.values()) set.remove(uuid);
    }

    private String statusLine(Territory t) {
        String owner = t.owner.isEmpty() ? "&7Neutral" : "&f" + manager.gang(t.owner).name;
        StringBuilder sb = new StringBuilder("&7[&e").append(t.name).append("&7] &f").append(owner);
        if (!t.progressOwner.isEmpty() && !t.progressOwner.equals(t.owner)) {
            Gang g = manager.gang(t.progressOwner);
            sb.append(" &7- &f").append(g != null ? g.name : t.progressOwner).append(" &b").append(t.progress).append("%");
        }
        if (warActive) sb.append(" &4[WAR]");
        return sb.toString();
    }

    // ========================= KILL TRACKING =========================

    @EventHandler
    public void onKill(PlayerDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null || killer.equals(e.getEntity())) return;
        String g = manager.gangOf(killer.getUniqueId());
        if (g != null) manager.recordKill(g, killer.getUniqueId());
    }

    // ========================= HELPERS =========================

    private List<String> playerNames() { return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(); }
    private List<String> territoryIds() { return manager.territories().stream().map(t -> t.id).toList(); }
    private List<String> gangMemberNames(CommandContext c) {
        if (!c.isPlayer()) return List.of();
        String g = manager.gangOf(c.player().getUniqueId());
        return g == null ? List.of() : manager.membersOf(g).stream().map(this::nameOf).toList();
    }
    private String nameOf(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        String n = Bukkit.getOfflinePlayer(uuid).getName();
        return n == null ? uuid.toString().substring(0, 8) : n;
    }

    public GangManager manager() { return manager; }
    public int weeklyDrugs() { return weeklyDrugs; }
    public static NamespacedKey drugKey() { return DRUG_KEY; }
    public void publishJoin(String gangId, String playerName) {
        ctx.events().publish("gangs.election", Map.of("type", "join", "gang", gangId, "player", playerName));
    }

    private static void cancelQuietly(ScheduledTask task) {
        if (task != null) try { task.cancel(); } catch (IllegalStateException ignored) {}
    }
}
