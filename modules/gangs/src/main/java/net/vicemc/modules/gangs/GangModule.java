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
import java.time.Instant;
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
 * Two-gang faction system with territory wars, biweekly leader elections,
 * kill tracking, gang banks, and drug boosters.
 */
public final class GangModule implements ViceModule, Listener {

    private static final NamespacedKey DRUG_KEY = NamespacedKey.fromString("vicemc:gang_drug");

    private ViceModuleContext ctx;
    private YamlConfig config;
    private GangManager manager;
    private GangGui gui;
    private ScheduledTask electionTask;
    private ScheduledTask territoryTask;
    private ScheduledTask warSchedulerTask;
    private int weeklyDrugs = 5;
    private int captureTickSeconds = 5;
    private long lastOpenedWeek = -1;
    private long lastResolvedWeek = -1;
    private boolean adminWarToggle = false;
    private boolean warActive = false;
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
        return "2.0.0";
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
        if (open != -1) lastOpenedWeek = open;
        if (resolved != -1) lastResolvedWeek = resolved;

        registerCommands();
        scheduleElections();
        scheduleTerritoryTick();
        scheduleWarWindow();
        warnMissingRegions();
        ctx.logger().info("Gang module v2.0 ready.");
    }

    private void warnMissingRegions() {
        for (Territory t : manager.territories()) {
            if (ctx.regions().byTag(t.regionTag).isEmpty()) {
                ctx.logger().warning("Territory '" + t.id + "' has no region tagged '"
                        + t.regionTag + "'. Create it with /vregion pos1 + pos2 + create <id> " + t.regionTag + ".");
            }
        }
    }

    @Override
    public void onDisable() {
        cancelQuietly(electionTask);
        cancelQuietly(territoryTask);
        cancelQuietly(warSchedulerTask);
        ctx.storage().setModuleData("gangs", "election:openWeek", String.valueOf(lastOpenedWeek));
        ctx.storage().setModuleData("gangs", "election:resolvedWeek", String.valueOf(lastResolvedWeek));
    }

    // ========================= COMMANDS =========================

    private void registerCommands() {
        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("gang")
                .description("Gang membership, territories, votes and drugs")
                .executes(this::gang)
                .tabulates((c, a) -> {
                    if (a.size() <= 1) return List.of("join", "leave", "info", "members", "vote", "drugs", "territories", "kick", "bank", "promote", "demote", "setleader", "togglewar");
                    switch (a.get(0)) {
                        case "join" -> { return List.of("north", "south"); }
                        case "kick", "promote", "demote", "setleader" -> { return playerNames(); }
                        case "bank" -> { return List.of("deposit", "withdraw", "balance"); }
                        default -> { return List.of(); }
                    }
                })
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("vote")
                .description("Cast your biweekly gang vote")
                .executes(this::vote)
                .tabulates((c, a) -> a.size() <= 1 ? gangMemberNames(c) : List.of())
                .build());
    }

    // ========================= WAR EVENT SCHEDULING =========================

    private void scheduleWarWindow() {
        warSchedulerTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(),
                task -> checkWarWindow(), 60L, 600L);
    }

    private void checkWarWindow() {
        ZoneId zone = ZoneId.of("Europe/Berlin");
        LocalDateTime now = LocalDateTime.now(zone);
        DayOfWeek warDay = getDayConfig("war.start-day", DayOfWeek.FRIDAY);
        DayOfWeek warEndDay = getDayConfig("war.end-day", DayOfWeek.SUNDAY);
        int warStartHour = config.getInt("war.start-hour", 20);
        int warEndHour = config.getInt("war.end-hour", 23);

        boolean inScheduledWindow = isWithinDayRange(now.getDayOfWeek(), warDay, warEndDay)
                && now.getHour() >= warStartHour && now.getHour() < warEndHour;

        boolean shouldBeActive = inScheduledWindow || adminWarToggle;

        if (shouldBeActive && !warActive) {
            warActive = true;
            manager.setWarActive(true);
            ctx.notifications().broadcast("&6&l⚔ TERRITORY WAR &7has begun! Capture territories for rewards!");
            ctx.events().publish("gangs.war", Map.of("active", "true"));
        } else if (!shouldBeActive && warActive && !adminWarToggle) {
            warActive = false;
            manager.setWarActive(false);
            ctx.notifications().broadcast("&7Territory war has ended. Territories captured remain yours.");
            ctx.events().publish("gangs.war", Map.of("active", "false"));
        }
    }

    private boolean isWithinDayRange(DayOfWeek current, DayOfWeek start, DayOfWeek end) {
        int cur = current.getValue();
        int s = start.getValue();
        int e = end.getValue();
        if (s <= e) {
            return cur >= s && cur <= e;
        } else {
            return cur >= s || cur <= e;
        }
    }

    private DayOfWeek getDayConfig(String key, DayOfWeek def) {
        String name = config.getString(key, def.name());
        try {
            return DayOfWeek.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return def;
        }
    }

    public void toggleWar(Player admin) {
        adminWarToggle = !adminWarToggle;
        if (adminWarToggle) {
            warActive = true;
            manager.setWarActive(true);
            ctx.notifications().broadcast("&6&l⚔ TERRITORY WAR &7toggled ON by an admin!");
        } else {
            if (!isWithinScheduledWindow()) {
                warActive = false;
                manager.setWarActive(false);
                ctx.notifications().broadcast("&7Territory war toggled OFF by an admin.");
            } else {
                ctx.notifications().msg(admin, "&7War is still within the scheduled window - cannot deactivate manually.");
                adminWarToggle = true;
            }
        }
    }

    private boolean isWithinScheduledWindow() {
        ZoneId zone = ZoneId.of("Europe/Berlin");
        LocalDateTime now = LocalDateTime.now(zone);
        DayOfWeek warDay = getDayConfig("war.start-day", DayOfWeek.FRIDAY);
        DayOfWeek warEndDay = getDayConfig("war.end-day", DayOfWeek.SUNDAY);
        int warStartHour = config.getInt("war.start-hour", 20);
        int warEndHour = config.getInt("war.end-hour", 23);
        return isWithinDayRange(now.getDayOfWeek(), warDay, warEndDay)
                && now.getHour() >= warStartHour && now.getHour() < warEndHour;
    }

    public boolean isWarActive() {
        return warActive;
    }

    // ========================= ELECTION SCHEDULING =========================

    private void scheduleElections() {
        electionTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(),
                task -> tickElections(), 60L, 600L);
    }

    private void tickElections() {
        DayOfWeek electionDay = getDayConfig("election.day", DayOfWeek.SATURDAY);
        int electionHour = config.getInt("election.hour", 12);
        int windowHours = config.getInt("election.window-hours", 24);
        int electionWeeks = config.getInt("election.every-weeks", 2);

        LocalDate today = LocalDate.now();
        long week = today.toEpochDay() / 7;

        boolean isElectionWeek = (week % electionWeeks) == 0;
        if (!isElectionWeek) {
            lastOpenedWeek = -1;
            return;
        }

        LocalDateTime start = today.with(TemporalAdjusters.previousOrSame(electionDay)).atTime(electionHour, 0);
        LocalDateTime end = start.plusHours(windowHours);
        LocalDateTime now = LocalDateTime.now();

        if (!now.isBefore(start) && now.isBefore(end)) {
            if (lastOpenedWeek != week) {
                lastOpenedWeek = week;
                ctx.storage().setModuleData("gangs", "election:openWeek", String.valueOf(week));
                ctx.notifications().broadcast("&6⚔ Biweekly gang election is now open! Use &e/vote&6 to choose your leader.");
                ctx.events().publish("gangs.election", Map.of("type", "open", "week", String.valueOf(week)));
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
        manager.resetTermKills();

        StringBuilder results = new StringBuilder("&6⚔ Gang elections closed! New leaders:");
        for (Map.Entry<Gang, UUID> entry : winners.entrySet()) {
            Gang gang = entry.getKey();
            UUID winner = entry.getValue();
            if (winner != null) {
                manager.setLeader(gang.id, winner);
                results.append("\n  &f").append(gang.name).append(" &7→ &e").append(nameOf(winner));
                results.append(" &7(").append(manager.killsThisTerm(gang.id, winner)).append(" kills)");
                ctx.events().publish("gangs.election", Map.of(
                        "type", "result", "week", String.valueOf(week),
                        "gang", gang.id, "leader", winner.toString()));
            } else {
                results.append("\n  &7").append(gang.name).append(" &7→ no votes (leader unchanged)");
            }
        }
        ctx.notifications().broadcast(results.toString());
    }

    public boolean electionOpen() {
        DayOfWeek electionDay = getDayConfig("election.day", DayOfWeek.SATURDAY);
        int electionHour = config.getInt("election.hour", 12);
        int windowHours = config.getInt("election.window-hours", 24);
        int electionWeeks = config.getInt("election.every-weeks", 2);

        LocalDate today = LocalDate.now();
        long week = today.toEpochDay() / 7;
        if ((week % electionWeeks) != 0) return false;

        LocalDateTime start = today.with(TemporalAdjusters.previousOrSame(electionDay)).atTime(electionHour, 0);
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(start) && now.isBefore(start.plusHours(windowHours));
    }

    // ========================= TERRITORY CAPTURE =========================

    private void scheduleTerritoryTick() {
        territoryTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(),
                task -> tickTerritories(), 60L, captureTickSeconds * 20L);
    }

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
                if (g != null) countByGang.merge(g, 1, Integer::sum);
            }

            int rate = captureRate(t);
            if (insidePlayers.isEmpty()) {
                if (t.progress > 0) manager.updateProgress(t.id, t.progress - rate, "");
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
                    if (t.progress > 0) manager.updateProgress(t.id, t.progress - rate, "");
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

    private void notifyTerritoryStatus(Territory t, List<Player> insidePlayers) {
        String line = statusLine(t);
        String key = t.owner + "|" + t.progressOwner + "|" + (t.progress / 10);
        if (key.equals(lastStatusKey.get(t.id))) return;
        lastStatusKey.put(t.id, key);
        for (Player p : insidePlayers) {
            ctx.notifications().action(p, line);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Location from = event.getFrom();
        Location to = event.getTo();
        for (Territory t : manager.territories()) {
            boolean wasInside = ctx.regions().isInside(from, t.regionTag);
            boolean nowInside = ctx.regions().isInside(to, t.regionTag);
            if (wasInside == nowInside) continue;
            Set<UUID> set = inside.computeIfAbsent(t.id, k -> ConcurrentHashMap.newKeySet());
            if (nowInside) set.add(uuid);
            else set.remove(uuid);
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
        for (Set<UUID> set : inside.values()) set.remove(uuid);
    }

    private int captureRate(Territory t) {
        return Math.max(1, (int) Math.ceil(100.0 * captureTickSeconds / t.captureSeconds));
    }

    private void capture(Territory t, String gangId) {
        manager.captureTerritory(t.id, gangId);
        Gang gang = manager.gang(gangId);
        String name = gang == null ? gangId : gang.name;
        ctx.notifications().broadcast("&6⚔ " + t.name + " &7captured by &f" + name + "&7!");

        if (warActive) {
            distributeRewards(t, gangId);
        }

        ctx.events().publish("gangs.territory", Map.of(
                "zone", t.id, "gang", gangId,
                "war", String.valueOf(warActive)));
    }

    private void distributeRewards(Territory t, String gangId) {
        if (t.rewardMoney > 0) {
            manager.bankDeposit(gangId, t.rewardMoney);
            ctx.notifications().broadcast("&a+" + String.format("%.0f", t.rewardMoney) + "$ added to " + manager.gang(gangId).name + " bank.");
        }
        if (t.rewardGuns > 0) {
            Gang gang = manager.gang(gangId);
            if (gang != null) {
                for (UUID uuid : manager.membersOf(gangId)) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null && p.isOnline()) {
                        ItemStack gun = ItemBuilder.of(Material.GUNPOWDER)
                                .name("&cWar Reward - Explosive Charge")
                                .lore("&7Captured during territory war", "&7Territory: " + t.name)
                                .build();
                        var leftover = p.getInventory().addItem(gun);
                        for (int i = 0; i < t.rewardGuns - 1; i++) {
                            leftover = p.getInventory().addItem(gun);
                        }
                        if (!leftover.isEmpty()) {
                            leftover.values().forEach(item -> p.getWorld().dropItemNaturally(p.getLocation(), item));
                        }
                        ctx.notifications().msg(p, "&6You received " + t.rewardGuns + "x &cWar Rewards &6from " + t.name + "!");
                    }
                }
            }
        }
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
        if (warActive) sb.append(" &4[WAR]");
        return sb.toString();
    }

    // ========================= KILL TRACKING =========================

    @EventHandler
    public void onKill(PlayerDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        if (killer.equals(event.getEntity())) return;

        String killerGang = manager.gangOf(killer.getUniqueId());
        if (killerGang != null) {
            manager.recordKill(killerGang, killer.getUniqueId());
        }
    }

    // ========================= /gang COMMAND =========================

    private void gang(CommandContext c) {
        if (!c.isPlayer()) {
            c.error("This is a player command.");
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
            case "bank" -> gui.openBank(c.player());
            case "promote" -> promote(c);
            case "demote" -> demote(c);
            case "setleader" -> setLeaderCmd(c);
            case "togglewar" -> toggleWarCmd(c);
            default -> gui.openDashboard(c.player());
        }
    }

    private void join(CommandContext c) {
        if (!c.isPlayer()) return;
        if (c.size() < 2) {
            c.usage("/gang join <north|south>");
            return;
        }
        Player player = c.player();
        if (manager.gangOf(player.getUniqueId()) != null) {
            c.error("You are already in a gang. Use /gang leave first.");
            return;
        }
        String gangId = c.arg(1).toLowerCase();
        Gang gang = manager.gang(gangId);
        if (gang == null) {
            c.error("Unknown gang. Valid: north, south");
            return;
        }
        if (!manager.canJoin(gangId)) {
            int diff = manager.memberDiff();
            c.error("That gang has too many more members than the other (" + diff + " diff). Wait for balance.");
            return;
        }
        if (manager.join(player.getUniqueId(), gangId)) {
            c.msg("&aYou joined &f" + gang.name + "&a.");
            ctx.events().publish("gangs.election", Map.of(
                    "type", "join", "gang", gangId, "player", player.getName()));
        } else {
            c.error("Could not join that gang.");
        }
    }

    private void leave(CommandContext c) {
        if (!c.isPlayer()) return;
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
        if (!c.isPlayer()) return;
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
        if (!manager.isHigherRank(player.getUniqueId(), gangId)) {
            c.error("Only the leader or lieutenants can kick members.");
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
            c.error("You cannot kick yourself.");
            return;
        }
        manager.leave(target.getUniqueId());
        c.msg("&aYou kicked &f" + target.getName() + "&a from your gang.");
        ctx.notifications().msg(target, "&cYou were kicked from your gang.");
    }

    private void promote(CommandContext c) {
        if (!c.isPlayer()) return;
        if (c.size() < 2) {
            c.usage("/gang promote <player>");
            return;
        }
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) {
            c.error("You are not in a gang.");
            return;
        }
        if (!manager.isLeader(player.getUniqueId(), gangId)) {
            c.error("Only the gang leader can promote members.");
            return;
        }
        Player target = c.playerArg(1);
        if (target == null || !manager.isMember(target.getUniqueId(), gangId)) {
            c.error("Player not found or not in your gang.");
            return;
        }
        if (manager.isLieutenant(target.getUniqueId(), gangId)) {
            c.error(target.getName() + " is already a lieutenant.");
            return;
        }
        manager.promoteToLieutenant(gangId, target.getUniqueId());
        c.msg("&aYou promoted &f" + target.getName() + " &ato lieutenant.");
        ctx.notifications().msg(target, "&6You have been promoted to lieutenant!");
    }

    private void demote(CommandContext c) {
        if (!c.isPlayer()) return;
        if (c.size() < 2) {
            c.usage("/gang demote <player>");
            return;
        }
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) {
            c.error("You are not in a gang.");
            return;
        }
        if (!manager.isLeader(player.getUniqueId(), gangId)) {
            c.error("Only the gang leader can demote members.");
            return;
        }
        Player target = c.playerArg(1);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        if (!manager.isLieutenant(target.getUniqueId(), gangId)) {
            c.error(target.getName() + " is not a lieutenant.");
            return;
        }
        manager.demoteFromLieutenant(gangId, target.getUniqueId());
        c.msg("&aYou demoted &f" + target.getName() + "&a from lieutenant.");
        ctx.notifications().msg(target, "&cYou have been demoted from lieutenant.");
    }

    private void setLeaderCmd(CommandContext c) {
        if (!c.isPlayer()) return;
        if (c.size() < 2) {
            c.usage("/gang setleader <player>");
            return;
        }
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) {
            c.error("You are not in a gang.");
            return;
        }
        if (!manager.isLeader(player.getUniqueId(), gangId)) {
            c.error("Only the current leader can appoint a new leader.");
            return;
        }
        Player target = c.playerArg(1);
        if (target == null || !manager.isMember(target.getUniqueId(), gangId)) {
            c.error("Player not found or not in your gang.");
            return;
        }
        manager.setLeader(gangId, target.getUniqueId());
        c.msg("&aYou appointed &f" + target.getName() + " &as the new leader.");
        ctx.notifications().msg(target, "&6You have been appointed gang leader!");
    }

    private void toggleWarCmd(CommandContext c) {
        if (!c.isPlayer()) return;
        Player player = c.player();
        if (!player.hasPermission("vicemc.gangs.admin")) {
            c.error("You don't have permission to toggle wars.");
            return;
        }
        toggleWar(player);
    }

    private void info(CommandContext c) {
        if (!c.isPlayer()) return;
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) {
            c.msg("&7You are not in a gang. Use &e/gang join&7.");
            return;
        }
        Gang gang = manager.gang(gangId);
        if (gang == null) {
            manager.leave(player.getUniqueId());
            c.msg("&7Your gang no longer exists.");
            return;
        }
        UUID leader = manager.leaderOf(gangId);
        c.msg("&6" + gang.name);
        c.msg("  &7Leader: &e" + (leader != null ? nameOf(leader) : "&7none (vote next election)"));
        c.msg("  &7Lieutenants: &f" + manager.lieutenantsOf(gangId).size());
        c.msg("  &7Members: &f" + manager.memberCount(gangId));
        c.msg("  &7Territories: &f" + manager.territoriesControlled(gangId) + " &7of &f" + manager.territories().size());
        c.msg("  &7Bank: &a$" + String.format("%.2f", manager.bankBalance(gangId)));
        c.msg("  &7War active: " + (warActive ? "&aYes" : "&7No"));
    }

    private void members(CommandContext c) {
        if (!c.isPlayer()) return;
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) {
            c.error("You are not in a gang.");
            return;
        }
        Gang gang = manager.gang(gangId);
        if (gang == null) {
            manager.leave(player.getUniqueId());
            c.error("Your gang no longer exists.");
            return;
        }
        c.msg("&6Members of " + gang.name + "&6:");
        UUID leader = manager.leaderOf(gangId);
        for (UUID u : manager.membersOf(gangId)) {
            String rank = manager.rankName(u, gangId);
            String prefix = u.equals(leader) ? "&6" : manager.isLieutenant(u, gangId) ? "&e" : "&7";
            c.msg("  " + prefix + nameOf(u) + " &7- " + rank);
        }
    }

    private void vote(CommandContext c) {
        if (!c.isPlayer()) return;
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) {
            c.error("You are not in a gang.");
            return;
        }
        if (manager.gang(gangId) == null) {
            manager.leave(player.getUniqueId());
            c.error("Your gang no longer exists.");
            return;
        }
        if (!electionOpen()) {
            c.error("Voting is not open right now. Elections run biweekly on Saturday.");
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
        int votes = manager.voteCount(gangId, candidate.getUniqueId());
        ctx.notifications().msg(voter, "&aYour vote for &f" + candidate.getName() + " &ahas been recorded. (" + votes + " total)");
        ctx.notifications().msg(candidate, "&eYou received a vote from " + voter.getName() + ". (" + votes + " total)");
    }

    private void drugs(CommandContext c) {
        if (!c.isPlayer()) return;
        Player player = c.player();
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null) {
            c.error("You are not in a gang.");
            return;
        }
        if (!manager.controlsAny(gangId)) {
            c.error("Your gang controls no territory. Capture a contested zone first.");
            return;
        }
        Gang gang = manager.gang(gangId);
        if (gang == null) {
            manager.leave(player.getUniqueId());
            c.error("Your gang no longer exists.");
            return;
        }
        int claim = manager.claimDrug(player.getUniqueId(), weeklyDrugs);
        if (claim == -1) {
            c.error("You already claimed your drugs this week.");
            return;
        }
        Material material = Material.matchMaterial(gang.drugMaterial);
        if (material == null) material = Material.SUGAR;
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
        if (gangId == null) return;
        Gang gang = manager.gang(gangId);
        if (gang == null) return;
        PotionEffectType effect = PotionEffectType.getByName(gang.drugEffect);
        if (effect == null) effect = PotionEffectType.HASTE;
        int amplifier = Math.max(0, gang.drugAmplifier - 1);
        event.getPlayer().addPotionEffect(new PotionEffect(effect,
                gang.drugDurationSeconds * 20, amplifier, false, false, true));
        ctx.notifications().msg(event.getPlayer(),
                "&8You feel the " + gang.drugName + " kicking in - stay out of sight.");
    }

    // ========================= HELPERS =========================

    private List<String> playerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private List<String> gangMemberNames(CommandContext c) {
        if (!c.isPlayer()) return List.of();
        String gangId = manager.gangOf(c.player().getUniqueId());
        if (gangId == null) return List.of();
        return manager.membersOf(gangId).stream().map(this::nameOf).toList();
    }

    private String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    public GangManager manager() {
        return manager;
    }

    public int weeklyDrugs() {
        return weeklyDrugs;
    }

    public static NamespacedKey drugKey() {
        return DRUG_KEY;
    }

    public void publishJoin(String gangId, String playerName) {
        ctx.events().publish("gangs.election", Map.of(
                "type", "join", "gang", gangId, "player", playerName));
    }

    private static void cancelQuietly(ScheduledTask task) {
        if (task == null) return;
        try {
            task.cancel();
        } catch (IllegalStateException ignored) {
        }
    }
}
