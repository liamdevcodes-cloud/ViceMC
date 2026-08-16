package net.vicemc.modules.gangs;

import net.kyori.adventure.text.Component;
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
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The two gangs (North Side / South Side), weekly leader elections, contested
 * territories fought over with stand-and-hold capture, and drug boosters that
 * unlock for a gang once it controls a contested territory.
 */
public final class GangModule implements ViceModule, Listener {

    private static final NamespacedKey DRUG_KEY = NamespacedKey.fromString("vicemc:gang_drug");

    private ViceModuleContext ctx;
    private YamlConfig config;
    private GangManager manager;
    private GangGui gui;
    private ScheduledTask electionTask;
    private ScheduledTask territoryTask;
    private int weeklyDrugs = 5;
    private int captureTickSeconds = 5;
    private long lastOpenedWeek = -1;
    private long lastResolvedWeek = -1;
    private final Map<String, Set<UUID>> inside = new ConcurrentHashMap<>();
    private final Map<String, String> lastStatusKey = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "gangs";
    }

    @Override
    public String displayName() {
        return "Vice Gangs";
    }

    @Override
    public String version() {
        return "1.1.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("gangs.yml");
        this.manager = new GangManager(ctx);
        this.gui = new GangGui(ctx, manager, this);
        this.weeklyDrugs = config.getInt("weekly-drugs", 5);
        this.captureTickSeconds = Math.max(1, config.getInt("capture.tick-seconds", 5));

        Bukkit.getPluginManager().registerEvents(this, ctx.plugin());

        long open = ctx.storage().getModuleData("gangs", "election:openWeek")
                .map(Long::parseLong).orElse(-1L);
        long resolved = ctx.storage().getModuleData("gangs", "election:resolvedWeek")
                .map(Long::parseLong).orElse(-1L);
        if (open != -1) {
            lastOpenedWeek = open;
        }
        if (resolved != -1) {
            lastResolvedWeek = resolved;
        }

        registerCommands();
        scheduleElections();
        scheduleTerritoryTick();
        warnMissingRegions();
        ctx.logger().info("Gang module ready.");
    }

    /** Flags configured contested zones that have no region defined yet, so admins know to set them up. */
    private void warnMissingRegions() {
        for (Territory t : manager.territories()) {
            if (ctx.regions().byTag(t.regionTag).isEmpty()) {
                ctx.logger().warning("Contested territory '" + t.id + "' has no region tagged '"
                        + t.regionTag + "'. Create it with /vregion pos1 + pos2 + create <id> " + t.regionTag + ".");
            }
        }
    }

    @Override
    public void onDisable() {
        if (electionTask != null) {
            cancelQuietly(electionTask);
            electionTask = null;
        }
        if (territoryTask != null) {
            cancelQuietly(territoryTask);
            territoryTask = null;
        }
        ctx.storage().setModuleData("gangs", "election:openWeek", String.valueOf(lastOpenedWeek));
        ctx.storage().setModuleData("gangs", "election:resolvedWeek", String.valueOf(lastResolvedWeek));
    }

    private void registerCommands() {
        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("gang")
                .description("Gang membership, territories, votes and drugs")
                .executes(this::gang)
                .tabulates((c, a) -> a.size() <= 1
                        ? List.of("join", "leave", "info", "members", "vote", "drugs", "territories", "kick")
                        : a.get(0).equals("join") ? gangIds() : playerNames())
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("vote")
                .description("Cast your weekly gang vote")
                .executes(this::vote)
                .tabulates((c, a) -> a.size() <= 1 ? gangMemberNames(c) : List.of())
                .build());
    }

    private void scheduleElections() {
        electionTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(),
                task -> tickElections(), 60L, 600L);
    }

    private void scheduleTerritoryTick() {
        territoryTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(),
                task -> tickTerritories(), 60L, captureTickSeconds * 20L);
    }

    private void tickElections() {
        String dayName = config.getString("election.day", "SUNDAY");
        DayOfWeek day;
        try {
            day = DayOfWeek.valueOf(dayName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            day = DayOfWeek.SUNDAY;
        }
        int hour = config.getInt("election.hour", 12);
        int windowHours = config.getInt("election.window-hours", 24);

        LocalDate today = LocalDate.now();
        long week = today.toEpochDay() / 7;
        LocalDateTime start = today.with(TemporalAdjusters.previousOrSame(day)).atTime(hour, 0);
        LocalDateTime end = start.plusHours(windowHours);
        LocalDateTime now = LocalDateTime.now();

        if (!now.isBefore(start) && now.isBefore(end)) {
            if (lastOpenedWeek != week) {
                lastOpenedWeek = week;
                ctx.storage().setModuleData("gangs", "election:openWeek", String.valueOf(week));
                ctx.notifications().broadcast("&6\u2694 Weekly gang election is now open! "
                        + "Use &e/vote&6 to choose your leader.");
                ctx.events().publish("gangs.election", Map.of("type", "open", "week", week));
            }
        } else if (!now.isBefore(end) && lastResolvedWeek != week) {
            lastResolvedWeek = week;
            ctx.storage().setModuleData("gangs", "election:resolvedWeek", String.valueOf(week));
            resolveElections(week);
        }
    }

    private void resolveElections(long week) {
        Map<Gang, UUID> winners = new java.util.LinkedHashMap<>();
        for (Gang gang : manager.all()) {
            winners.put(gang, manager.countWinner(gang.id));
        }
        manager.clearVotes();
        StringBuilder results = new StringBuilder("&6\u2694 Gang elections closed! New leaders:");
        for (Map.Entry<Gang, UUID> entry : winners.entrySet()) {
            Gang gang = entry.getKey();
            UUID winner = entry.getValue();
            if (winner != null) {
                manager.setLeader(gang.id, winner);
                results.append("\n  &f").append(gang.name).append(" &7\u2192 &e").append(nameOf(winner));
                ctx.events().publish("gangs.election", Map.of(
                        "type", "result", "week", week,
                        "gang", gang.id, "leader", winner.toString()));
            } else {
                results.append("\n  &7").append(gang.name).append(" &7\u2192 no votes (leader unchanged)");
            }
        }
        ctx.notifications().broadcast(results.toString());
    }

    private boolean isElectionWindowOpen() {
        String dayName = config.getString("election.day", "SUNDAY");
        DayOfWeek day;
        try {
            day = DayOfWeek.valueOf(dayName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            day = DayOfWeek.SUNDAY;
        }
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.with(TemporalAdjusters.previousOrSame(day))
                .atTime(config.getInt("election.hour", 12), 0);
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(start) && now.isBefore(start.plusHours(config.getInt("election.window-hours", 24)));
    }

    /** Whether the weekly vote window is currently open (used by the gang GUI). */
    public boolean electionOpen() {
        return isElectionWindowOpen();
    }

    /** Weekly drug claim limit (used by the gang GUI). */
    public int weeklyDrugs() {
        return weeklyDrugs;
    }

    /** NBT key used to tag gang drugs (used by the gang GUI). */
    public static NamespacedKey drugKey() {
        return DRUG_KEY;
    }

    /** Publishes a gang join event for the Discord bridge (used by the gang GUI). */
    public void publishJoin(String gangId, String playerName) {
        ctx.events().publish("gangs.election", Map.of(
                "type", "join", "gang", gangId, "player", playerName));
    }

    // --- territory capture ------------------------------------------------

    private void tickTerritories() {
        for (Territory t : manager.territories()) {
            Map<String, Integer> countByGang = new HashMap<>();
            List<Player> insidePlayers = new ArrayList<>();
            Set<UUID> uuids = inside.getOrDefault(t.id, Set.of());
            for (UUID uuid : uuids) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) {
                    inside.get(t.id).remove(uuid);
                    continue;
                }
                insidePlayers.add(p);
                String g = manager.gangOf(uuid);
                if (g != null) {
                    countByGang.merge(g, 1, Integer::sum);
                }
            }

            int rate = captureRate(t);
            if (insidePlayers.isEmpty()) {
                if (t.progress > 0) {
                    manager.updateProgress(t.id, t.progress - rate, "");
                }
                continue;
            }

            if (t.owner.isEmpty()) {
                Map.Entry<String, Integer> top = null;
                boolean tie = false;
                for (Map.Entry<String, Integer> e : countByGang.entrySet()) {
                    if (top == null || e.getValue() > top.getValue()) {
                        top = e;
                        tie = false;
                    } else if (e.getValue() == top.getValue()) {
                        tie = true;
                    }
                }
                if (top == null || tie) {
                    if (t.progress > 0) {
                        manager.updateProgress(t.id, t.progress - rate, "");
                    }
                } else {
                    int next = t.progressOwner.equals(top.getKey()) ? t.progress + rate : rate;
                    if (next >= 100) {
                        capture(t, top.getKey());
                    } else {
                        manager.updateProgress(t.id, next, top.getKey());
                    }
                }
            } else {
                String attackingGang = "";
                int attackers = 0;
                for (Map.Entry<String, Integer> e : countByGang.entrySet()) {
                    if (!e.getKey().equals(t.owner) && e.getValue() > attackers) {
                        attackers = e.getValue();
                        attackingGang = e.getKey();
                    }
                }
                int defenders = countByGang.getOrDefault(t.owner, 0);
                if (attackers > 0 && attackers > defenders) {
                    int next = t.progressOwner.equals(attackingGang) ? t.progress + rate : rate;
                    if (next >= 100) {
                        capture(t, attackingGang);
                    } else {
                        manager.updateProgress(t.id, next, attackingGang);
                    }
                } else if (t.progress > 0) {
                    manager.updateProgress(t.id, t.progress - rate, "");
                }
            }

            notifyTerritoryStatus(t, insidePlayers);
        }
    }

    /** Sends the status line only when the capture state actually changed, so chat is not spammed. */
    private void notifyTerritoryStatus(Territory t, List<Player> insidePlayers) {
        String line = statusLine(t);
        String key = t.owner + "|" + t.progressOwner + "|" + (t.progress / 10);
        if (key.equals(lastStatusKey.get(t.id))) {
            return;
        }
        lastStatusKey.put(t.id, key);
        for (Player p : insidePlayers) {
            ctx.notifications().action(p, line);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Location from = event.getFrom();
        Location to = event.getTo();
        for (Territory t : manager.territories()) {
            boolean wasInside = ctx.regions().isInside(from, t.regionTag);
            boolean nowInside = ctx.regions().isInside(to, t.regionTag);
            if (wasInside == nowInside) {
                continue;
            }
            Set<UUID> set = inside.computeIfAbsent(t.id, k -> ConcurrentHashMap.newKeySet());
            if (nowInside) {
                set.add(uuid);
            } else {
                set.remove(uuid);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        for (Territory t : manager.territories()) {
            if (ctx.regions().isInside(loc, t.regionTag)) {
                inside.computeIfAbsent(t.id, k -> ConcurrentHashMap.newKeySet()).add(player.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        for (Set<UUID> set : inside.values()) {
            set.remove(uuid);
        }
    }

    private int captureRate(Territory t) {
        return Math.max(1, (int) Math.ceil(100.0 * captureTickSeconds / t.captureSeconds));
    }

    private void capture(Territory t, String gangId) {
        manager.captureTerritory(t.id, gangId);
        Gang gang = manager.gang(gangId);
        String name = gang == null ? gangId : gang.name;
        ctx.notifications().broadcast("&6\u2694 " + t.name + " &7has been captured by &f" + name + "&7!");
        ctx.events().publish("gangs.territory", Map.of("zone", t.id, "gang", gangId));
    }

    private String statusLine(Territory t) {
        String owner = t.owner.isEmpty() ? "&7Neutral" : "&f" + manager.gang(t.owner).name;
        StringBuilder sb = new StringBuilder("&7[&e").append(t.name).append("&7] Owner: ").append(owner);
        if (!t.progressOwner.isEmpty()) {
            Gang g = manager.gang(t.progressOwner);
            String gName = g == null ? t.progressOwner : g.name;
            if (t.owner.isEmpty()) {
                sb.append(" &7- &f").append(gName).append(" &7capturing &b").append(t.progress).append("%");
            } else if (!t.progressOwner.equals(t.owner)) {
                sb.append(" &7- &f").append(gName).append(" &7attacking &b").append(t.progress).append("%");
            }
        }
        return sb.toString();
    }

    // --- /gang ------------------------------------------------------------

    private void gang(CommandContext c) {
        if (!c.isPlayer()) {
            c.error("This is a player command - run it in game.");
            return;
        }
        switch (c.arg(0)) {
            case "join" -> join(c);
            case "leave" -> leave(c);
            case "info" -> info(c);
            case "members" -> members(c);
            case "vote" -> vote(c);
            case "drugs" -> drugs(c);
            case "territories" -> gui.openTerritories(c.player());
            case "kick" -> kick(c);
            default -> gui.openDashboard(c.player());
        }
    }

    private void join(CommandContext c) {
        if (!c.isPlayer()) {
            c.error("Only players can join a gang.");
            return;
        }
        if (c.size() < 2) {
            c.usage("/gang join <gang>");
            return;
        }
        Player player = c.player();
        if (manager.gangOf(player.getUniqueId()) != null) {
            c.error("You are already in a gang.");
            return;
        }
        String gangId = c.arg(1);
        Gang gang = manager.gang(gangId);
        if (gang == null) {
            c.error("Unknown gang. Valid: " + String.join(", ", gangIds()));
            return;
        }
        manager.join(player.getUniqueId(), gangId);
        c.msg("&aYou joined &f" + gang.name + "&a.");
        ctx.events().publish("gangs.election", Map.of(
                "type", "join", "gang", gangId, "player", player.getName()));
    }

    private void leave(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) {
            c.error("You are not in a gang.");
            return;
        }
        manager.leave(player.getUniqueId());
        c.msg("&aYou left your gang.");
    }

    private void kick(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (c.size() < 2) {
            c.usage("/gang kick <player>");
            return;
        }
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) {
            c.error("You are not in a gang.");
            return;
        }
        UUID leader = manager.leaderOf(gangId);
        if (leader == null || !leader.equals(player.getUniqueId())) {
            c.error("Only the gang leader can kick members.");
            return;
        }
        Player target = c.playerArg(1);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        if (!manager.isMember(target.getUniqueId(), gangId)) {
            c.error(target.getName() + " is not in your gang.");
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            c.error("You cannot kick yourself - leave instead.");
            return;
        }
        manager.leave(target.getUniqueId());
        c.msg("&aYou kicked &f" + target.getName() + "&a from your gang.");
        ctx.notifications().msg(target, "&cYou were kicked from your gang.");
    }

    private void info(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        String gangId = manager.gangOf(c.player().getUniqueId());
        if (gangId == null) {
            c.msg("&7You are not in a gang. Use &e/gang join&7.");
            return;
        }
        Gang gang = manager.gang(gangId);
        if (gang == null) {
            manager.leave(c.player().getUniqueId());
            c.msg("&7Your gang no longer exists - rejoin with &e/gang join&7.");
            return;
        }
        UUID leader = manager.leaderOf(gangId);
        c.msg("&6" + gang.name);
        c.msg("  &7Leader: &e" + (leader != null ? nameOf(leader) : "&7none (elections open each week)"));
        c.msg("  &7Members: &f" + manager.membersOf(gangId).size());
        c.msg("  &7Territories: &f" + manager.territoriesControlled(gangId)
                + " &7of &f" + manager.territories().size());
        c.msg("  &7Drug: &f" + gang.drugName + " &7- &f" + gang.drugEffect
                + " " + gang.drugAmplifier + " (" + gang.drugDurationSeconds + "s)");
        if (manager.controlsAny(gangId)) {
            c.msg("  &aTerritory controlled - drugs unlocked. Use &e/gang drugs&a.");
        } else {
            c.msg("  &cCapture a contested territory to unlock drugs. See &e/gang territories&c.");
        }
    }

    private void members(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        String gangId = manager.gangOf(c.player().getUniqueId());
        if (gangId == null) {
            c.error("You are not in a gang.");
            return;
        }
        Gang gang = manager.gang(gangId);
        if (gang == null) {
            manager.leave(c.player().getUniqueId());
            c.error("Your gang no longer exists - rejoin with /gang join.");
            return;
        }
        c.msg("&6Members of " + gang.name + "&6:");
        manager.membersOf(gangId).forEach(u -> c.msg("  &7- &f" + nameOf(u)));
    }

    private void vote(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) {
            c.error("You are not in a gang.");
            return;
        }
        if (manager.gang(gangId) == null) {
            manager.leave(player.getUniqueId());
            c.error("Your gang no longer exists - rejoin with /gang join.");
            return;
        }
        if (!electionOpen()) {
            c.error("Voting is not open right now. Elections run each week during the voting window.");
            return;
        }
        if (c.size() < 2) {
            gui.openVote(player, gangId, 1);
            return;
        }
        Player candidate = c.playerArg(1);
        if (candidate == null || !manager.isMember(candidate.getUniqueId(), gangId)) {
            c.error("Candidate must be a member of your gang.");
            return;
        }
        castVote(player, gangId, candidate);
    }

    private void castVote(Player voter, String gangId, Player candidate) {
        manager.vote(gangId, voter.getUniqueId(), candidate.getUniqueId());
        ctx.notifications().msg(voter, "&aYour vote for &f" + candidate.getName() + " &ahas been recorded.");
        ctx.notifications().msg(candidate, "&eYou received a vote from " + voter.getName() + ".");
    }

    private void drugs(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) {
            c.error("You are not in a gang.");
            return;
        }
        if (!manager.controlsAny(gangId)) {
            c.error("Your gang controls no territory - capture a contested zone first.");
            return;
        }
        Gang gang = manager.gang(gangId);
        if (gang == null) {
            manager.leave(player.getUniqueId());
            c.error("Your gang no longer exists - rejoin with /gang join.");
            return;
        }
        int claim = manager.claimDrug(player.getUniqueId(), weeklyDrugs);
        if (claim == -1) {
            c.error("You already claimed your drugs this week.");
            return;
        }
        Material material = Material.matchMaterial(gang.drugMaterial);
        if (material == null) {
            material = Material.SUGAR;
        }
        ItemStack drug = ItemBuilder.of(material)
                .name(gang.drugName)
                .lore("&7Gang booster: &f" + gang.drugEffect + " " + gang.drugAmplifier,
                        "&7Claimed " + claim + "/" + weeklyDrugs + " this week",
                        "&4Risk: arrest without a lawyer is likely")
                .tag(DRUG_KEY, gangId)
                .build();
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(drug);
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
        }
        c.msg("&aClaimed &f" + gang.drugName + "&a (" + claim + "/" + weeklyDrugs + " this week).");
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        String gangId = ItemBuilder.tag(event.getItem(), DRUG_KEY);
        if (gangId == null) {
            return;
        }
        Gang gang = manager.gang(gangId);
        if (gang == null) {
            return;
        }
        PotionEffectType effect = PotionEffectType.getByName(gang.drugEffect);
        if (effect == null) {
            effect = PotionEffectType.HASTE;
        }
        int amplifier = Math.max(0, gang.drugAmplifier - 1);
        event.getPlayer().addPotionEffect(new PotionEffect(effect,
                gang.drugDurationSeconds * 20, amplifier, false, false, true));
        ctx.notifications().msg(event.getPlayer(),
                "&8You feel the " + gang.drugName + " kicking in - stay out of sight.");
    }

    // --- helpers ----------------------------------------------------------

    private List<String> gangIds() {
        return manager.all().stream().map(g -> g.id).toList();
    }

    private List<String> gangMemberNames(CommandContext c) {
        if (!c.isPlayer()) {
            return List.of();
        }
        String gangId = manager.gangOf(c.player().getUniqueId());
        if (gangId == null) {
            return List.of();
        }
        return manager.membersOf(gangId).stream().map(this::nameOf).toList();
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

    private static void cancelQuietly(ScheduledTask task) {
        try {
            task.cancel();
        } catch (IllegalStateException ignored) {
        }
    }
}
