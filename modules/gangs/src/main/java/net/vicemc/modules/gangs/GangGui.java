package net.vicemc.modules.gangs;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.model.Region;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dark-modern player menus for gang life: dashboard, gang picker, weekly
 * leader vote and the contested-territory overview with capture status.
 */
public final class GangGui {

    private static final int PER_PAGE = 27;

    private final ViceModuleContext ctx;
    private final GangManager manager;
    private final GangModule module;
    private final Map<UUID, Integer> joinPage = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> territoryPage = new ConcurrentHashMap<>();

    public GangGui(ViceModuleContext ctx, GangManager manager, GangModule module) {
        this.ctx = ctx;
        this.manager = manager;
        this.module = module;
    }

    public void openDashboard(Player player) {
        UUID uuid = player.getUniqueId();
        String gangId = manager.gangOf(uuid);
        Gang gang = gangId == null ? null : manager.gang(gangId);
        UUID leader = gangId == null ? null : manager.leaderOf(gangId);
        boolean controlled = gangId != null && manager.controlsAny(gangId);
        int used = drugUsed(player);

        var builder = ctx.gui().builder(GuiKit.title("Gang"), 6);
        GuiKit.frame(builder);

        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&6How it works",
                "&7Two gangs: &bNorth Side&7 and &dSouth Side&7.",
                "&7Weekly elections pick each gang's leader.",
                "&7Gangs capture contested street zones with",
                "&7stand-and-hold fights; controlling a zone",
                "&7unlocks the gang's weekly drug booster."), GuiKit.NONE);

        ItemStack status;
        if (gang == null) {
            status = GuiKit.icon(Material.BARRIER, "&7No gang",
                    "&7Join a gang to fight for territory",
                    "&7and claim weekly drug boosters.");
        } else {
            Material mat = Material.matchMaterial(gang.drugMaterial);
            status = ItemBuilder.of(mat == null ? Material.SUGAR : mat)
                    .name("&e" + gang.name)
                    .lore("&7Leader: &f" + (leader != null ? nameOf(leader) : "&7none - vote this week"),
                            "&7Members: &f" + manager.membersOf(gangId).size(),
                            "&7Territories: &f" + manager.territoriesControlled(gangId)
                                    + " &7of &f" + manager.territories().size(),
                            "&7Drug: &f" + gang.drugName,
                            controlled ? "&aTerritory controlled - drugs unlocked"
                                    : "&cNo territory - capture a zone to unlock drugs")
                    .build();
        }
        builder.item(GuiKit.STATUS, status, GuiKit.NONE);

        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        if (gang == null) {
            builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.EMERALD, "&aJoin a gang",
                    "&7Pick from North Side or South Side."), (p, c) -> openJoin(p));
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.IRON_INGOT, "&7Not in a gang",
                    "&7You must join a gang first."), GuiKit.NONE);
        } else {
            boolean isLeader = uuid.equals(leader);
            builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.EMERALD, "&aVote for leader",
                    module.electionOpen()
                            ? "&7Pick who should lead " + gang.name + " this week."
                            : "&7Voting is closed right now."), (p, c) -> {
                if (module.electionOpen()) {
                    openVote(p, gangId, 1);
                } else {
                    ctx.notifications().warn(p, "Voting is not open right now.");
                }
            });
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.MAP, "&6Territories",
                    "&7View the contested zones your gang",
                    "&7fights over and their capture status.",
                    "&7Controlled: &f" + manager.territoriesControlled(gangId)), (p, c) -> openTerritories(p));
            builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.RED_WOOL, "&cLeave gang",
                    isLeader ? "&7Leaving removes you as leader." : "&7You will lose gang perks."), (p, c) -> {
                manager.leave(p.getUniqueId());
                ctx.notifications().msg(p, "&cYou left your gang.");
                openDashboard(p);
            });
            builder.item(GuiKit.ACTION_4, GuiKit.icon(controlled ? Material.GLOWSTONE_DUST : Material.GRAY_DYE,
                    controlled ? "&aClaim weekly drugs" : "&7Drugs locked",
                    controlled
                            ? "&7Claim your " + gang.drugName + " booster (" + used + "/" + module.weeklyDrugs() + " this week)."
                            : "&7Capture a contested territory to unlock drugs."), (p, c) -> {
                if (controlled) {
                    if (claimDrugs(p)) {
                        openDashboard(p);
                    }
                } else {
                    ctx.notifications().warn(p, "Your gang controls no territory yet.");
                }
            });
        }

        builder.item(GuiKit.ACTION_5, GuiKit.money(ctx.economy().balance(uuid)), GuiKit.NONE);

        if (gang != null) {
            List<UUID> members = manager.membersOf(gangId);
            int shown = Math.min(members.size(), 27);
            for (int i = 0; i < shown; i++) {
                UUID member = members.get(i);
                boolean isLeader = member.equals(leader);
                int slot = GuiKit.GRID_FIRST + i;
                builder.item(slot, GuiKit.playerHead(nameOf(member),
                        (isLeader ? "&6" : "&f") + nameOf(member),
                        isLeader ? "&6Gang leader" : "&7Member"), GuiKit.NONE);
            }
            if (members.size() > shown) {
                builder.item(GuiKit.GRID_FIRST + 27 - 1, GuiKit.icon(Material.PAPER,
                        "&7+" + (members.size() - shown) + " more members",
                        "&7Use /gang members for the full list."), GuiKit.NONE);
            }
        }

        builder.open(player);
    }

    private void openJoin(Player player) {
        List<Gang> gangs = manager.all();
        int pages = GuiKit.Pages.pages(gangs.size(), PER_PAGE);
        int page = Math.min(joinPage.getOrDefault(player.getUniqueId(), 1), pages);

        var builder = ctx.gui().builder(GuiKit.title("Pick a gang"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the gang menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.EMERALD, "&aJoin a gang",
                "&7North Side or South Side - you choose."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        List<Gang> slice = GuiKit.Pages.slice(gangs, page, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Gang gang = slice.get(i);
            Material mat = Material.matchMaterial(gang.drugMaterial);
            ItemStack item = ItemBuilder.of(mat == null ? Material.SUGAR : mat)
                    .name("&e" + gang.name)
                    .lore("&7Members: &f" + manager.membersOf(gang.id).size(),
                            "&7Drug: &f" + gang.drugName,
                            "&7Territories: &f" + manager.territoriesControlled(gang.id),
                            "&aClick to join")
                    .build();
            int slot = GuiKit.GRID_FIRST + i;
            builder.item(slot, item, (p, c) -> {
                manager.join(p.getUniqueId(), gang.id);
                ctx.notifications().msg(p, "&aYou joined " + gang.name + ".");
                module.publishJoin(gang.id, p.getName());
                openDashboard(p);
            });
        }

        if (pages > 1) {
            builder.item(GuiKit.PAGE_PREV, page > 1 ? GuiKit.prevPage(page) : GuiKit.pageGap(),
                    (p, c) -> {
                        if (page > 1) {
                            joinPage.put(p.getUniqueId(), page - 1);
                            openJoin(p);
                        }
                    });
            builder.item(GuiKit.PAGE_INDICATOR, GuiKit.pageIndicator(page, pages), GuiKit.NONE);
            builder.item(GuiKit.PAGE_NEXT, page < pages ? GuiKit.nextPage(page) : GuiKit.pageGap(),
                    (p, c) -> {
                        if (page < pages) {
                            joinPage.put(p.getUniqueId(), page + 1);
                            openJoin(p);
                        }
                    });
        }

        builder.open(player);
    }

    public void openVote(Player player, String gangId, int page) {
        Gang gang = manager.gang(gangId);
        List<UUID> members = manager.membersOf(gangId);
        int pages = GuiKit.Pages.pages(members.size(), PER_PAGE);
        int safe = Math.min(page, pages);

        var builder = ctx.gui().builder(GuiKit.title("Vote - " + gang.name), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the gang menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.GOLD_INGOT, "&6Weekly leader vote",
                "&7Cast your vote for the next leader.",
                "&7The winner leads the gang for the week."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        List<UUID> slice = GuiKit.Pages.slice(members, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            UUID member = slice.get(i);
            int slot = GuiKit.GRID_FIRST + i;
            builder.item(slot, GuiKit.playerHead(nameOf(member),
                    "&eVote for &f" + nameOf(member),
                    "&7Click to cast your vote."), (p, c) -> {
                Player candidate = Bukkit.getPlayer(member);
                if (candidate == null) {
                    ctx.notifications().warn(p, "That player is offline.");
                    return;
                }
                manager.vote(gangId, p.getUniqueId(), member);
                ctx.notifications().msg(p, "&aYour vote for " + nameOf(member) + " has been recorded.");
                ctx.notifications().msg(candidate, "&eYou received a vote from " + p.getName() + ".");
                p.closeInventory();
            });
        }

        if (pages > 1) {
            builder.item(GuiKit.PAGE_PREV, safe > 1 ? GuiKit.prevPage(safe) : GuiKit.pageGap(),
                    (p, c) -> {
                        if (safe > 1) {
                            openVote(p, gangId, safe - 1);
                        }
                    });
            builder.item(GuiKit.PAGE_INDICATOR, GuiKit.pageIndicator(safe, pages), GuiKit.NONE);
            builder.item(GuiKit.PAGE_NEXT, safe < pages ? GuiKit.nextPage(safe) : GuiKit.pageGap(),
                    (p, c) -> {
                        if (safe < pages) {
                            openVote(p, gangId, safe + 1);
                        }
                    });
        }

        builder.open(player);
    }

    public void openTerritories(Player player) {
        List<Territory> territories = manager.territories();
        int pages = GuiKit.Pages.pages(territories.size(), PER_PAGE);
        int page = Math.min(territoryPage.getOrDefault(player.getUniqueId(), 1), pages);

        var builder = ctx.gui().builder(GuiKit.title("Contested territories"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the gang menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.MAP, "&6Territory war",
                "&7Stand inside a neutral zone with more",
                "&7of your gang than the enemy to capture",
                "&7it. Click a zone to teleport there.",
                "&7Control unlocks your weekly drugs."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        List<Territory> slice = GuiKit.Pages.slice(territories, page, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Territory t = slice.get(i);
            String ownerName = t.owner.isEmpty() ? "&7Neutral" : "&f" + manager.gang(t.owner).name;
            String progressLine = progressLine(t);
            Material mat = t.owner.isEmpty() ? Material.LIGHT_GRAY_CONCRETE
                    : Material.matchMaterial(gangColor(t.owner));
            ItemStack item = ItemBuilder.of(mat == null ? Material.LIGHT_GRAY_CONCRETE : mat)
                    .name(t.name)
                    .lore("&7Owner: " + ownerName,
                            progressLine,
                            "&7Capture time: &f" + t.captureSeconds + "s",
                            "&aClick to teleport")
                    .build();
            int slot = GuiKit.GRID_FIRST + i;
            builder.item(slot, item, (p, c) -> teleportToTerritory(p, t));
        }

        if (pages > 1) {
            builder.item(GuiKit.PAGE_PREV, page > 1 ? GuiKit.prevPage(page) : GuiKit.pageGap(),
                    (p, c) -> {
                        if (page > 1) {
                            territoryPage.put(p.getUniqueId(), page - 1);
                            openTerritories(p);
                        }
                    });
            builder.item(GuiKit.PAGE_INDICATOR, GuiKit.pageIndicator(page, pages), GuiKit.NONE);
            builder.item(GuiKit.PAGE_NEXT, page < pages ? GuiKit.nextPage(page) : GuiKit.pageGap(),
                    (p, c) -> {
                        if (page < pages) {
                            territoryPage.put(p.getUniqueId(), page + 1);
                            openTerritories(p);
                        }
                    });
        }

        builder.open(player);
    }

    private String progressLine(Territory t) {
        if (t.progressOwner.isEmpty()) {
            return "&7Standstill - no side has the advantage";
        }
        Gang g = manager.gang(t.progressOwner);
        String gName = g == null ? t.progressOwner : g.name;
        return "&7Capturing: &f" + gName + " &b" + t.progress + "%";
    }

    private String gangColor(String gangId) {
        if ("north".equals(gangId)) {
            return "LIGHT_BLUE_CONCRETE";
        }
        if ("south".equals(gangId)) {
            return "MAGENTA_CONCRETE";
        }
        return "LIGHT_GRAY_CONCRETE";
    }

    private void teleportToTerritory(Player player, Territory t) {
        Set<Region> regions = ctx.regions().byTag(t.regionTag);
        if (regions.isEmpty()) {
            ctx.notifications().warn(player, "This territory has no region defined yet.");
            return;
        }
        Region region = regions.iterator().next();
        World world = Bukkit.getWorld(region.world());
        if (world == null) {
            ctx.notifications().warn(player, "This territory's world is not loaded.");
            return;
        }
        Location center = new Location(world,
                (region.minX() + region.maxX()) / 2.0 + 0.5,
                (region.minY() + region.maxY()) / 2.0,
                (region.minZ() + region.maxZ()) / 2.0 + 0.5);
        center.setY(world.getHighestBlockYAt(center.getBlockX(), center.getBlockZ()) + 1);
        player.teleport(center);
        ctx.notifications().msg(player, "&aTeleported to " + t.name + ".");
    }

    private boolean claimDrugs(Player player) {
        String gangId = manager.gangOf(player.getUniqueId());
        if (gangId == null || !manager.controlsAny(gangId)) {
            ctx.notifications().warn(player, "Your gang controls no territory - capture a contested zone first.");
            return false;
        }
        Gang gang = manager.gang(gangId);
        int claim = manager.claimDrug(player.getUniqueId(), module.weeklyDrugs());
        if (claim == -1) {
            ctx.notifications().warn(player, "You already claimed your drugs this week.");
            return false;
        }
        Material material = Material.matchMaterial(gang.drugMaterial);
        if (material == null) {
            material = Material.SUGAR;
        }
        ItemStack drug = ItemBuilder.of(material)
                .name(gang.drugName)
                .lore("&7Gang booster: &f" + gang.drugEffect + " " + gang.drugAmplifier,
                        "&7Claimed " + claim + "/" + module.weeklyDrugs() + " this week",
                        "&4Risk: arrest without a lawyer is likely")
                .tag(GangModule.drugKey(), gangId)
                .build();
        var leftover = player.getInventory().addItem(drug);
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
        }
        ctx.notifications().msg(player, "&aClaimed " + gang.drugName + " (" + claim + "/" + module.weeklyDrugs() + " this week).");
        return true;
    }

    private int drugUsed(Player player) {
        String week = String.valueOf(LocalDate.now().toEpochDay() / 7);
        return ctx.storage().getModuleData("gangs", "drugs:" + player.getUniqueId())
                .filter(v -> v.startsWith(week + ":"))
                .map(v -> Integer.parseInt(v.split(":")[1]))
                .orElse(0);
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
