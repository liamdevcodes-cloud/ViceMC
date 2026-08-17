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
 * Dark-modern player menus for gang life: dashboard, gang picker,
 * biweekly leader vote with kill stats, bank, member management,
 * and contested-territory overview with capture status and rewards.
 */
public final class GangGui {

    private static final int PER_PAGE = 27;

    private final ViceModuleContext ctx;
    private final GangManager manager;
    private final GangModule module;
    private final Map<UUID, Integer> joinPage = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> territoryPage = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> votePage = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> memberPage = new ConcurrentHashMap<>();

    public GangGui(ViceModuleContext ctx, GangManager manager, GangModule module) {
        this.ctx = ctx;
        this.manager = manager;
        this.module = module;
    }

    // ========================= DASHBOARD =========================

    public void openDashboard(Player player) {
        UUID uuid = player.getUniqueId();
        String gangId = manager.gangOf(uuid);
        Gang gang = gangId == null ? null : manager.gang(gangId);
        UUID leader = gangId == null ? null : manager.leaderOf(gangId);
        boolean controlled = gangId != null && manager.controlsAny(gangId);

        var builder = ctx.gui().builder(GuiKit.title("Gang"), 6);
        GuiKit.frame(builder);

        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&6How it works",
                "&7Two factions: &bNorth Side&7 and &dSouth Side&7.",
                "&7Every 2 weeks, members vote for a new leader.",
                "&7Fight for territories during war events (Fri-Sun 8PM).",
                "&7Capturing territory during war gives bank rewards.",
                "&7Use &e/gang bank&7 to manage gang funds."), GuiKit.NONE);

        ItemStack status;
        if (gang == null) {
            status = GuiKit.icon(Material.BARRIER, "&7No gang",
                    "&7Join a faction to fight for territory");
        } else {
            Material mat = Material.matchMaterial(gang.drugMaterial);
            status = ItemBuilder.of(mat == null ? Material.SUGAR : mat)
                    .name("&e" + gang.name)
                    .lore(
                            "&7Leader: &f" + (leader != null ? nameOf(leader) : "&7none"),
                            "&7Lieutenants: &f" + manager.lieutenantsOf(gangId).size(),
                            "&7Members: &f" + manager.memberCount(gangId) + " &7(max diff: 4)",
                            "&7Territories: &f" + manager.territoriesControlled(gangId) + " &7of &f" + manager.territories().size(),
                            "&7Bank: &a$" + String.format("%.2f", manager.bankBalance(gangId)),
                            module.isWarActive() ? "&4⚔ WAR ACTIVE" : "&7War: inactive")
                    .build();
        }
        builder.item(GuiKit.STATUS, status, GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        if (gang == null) {
            builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.EMERALD, "&aJoin a faction",
                    "&7Pick from North Side or South Side."), (p, c) -> openJoin(p));
            builder.item(GuiKit.ACTION_2, GuiKit.cta(Material.IRON_INGOT, "&7Not in a faction",
                    "&7You must join a faction first."), GuiKit.NONE);
        } else {
            boolean isLeader = uuid.equals(leader);
            builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.GOLD_INGOT, "&6Vote for leader",
                    module.electionOpen()
                            ? "&7Biweekly election is OPEN. Pick wisely."
                            : "&7Elections run every 2 weeks on Saturday.",
                    module.electionOpen() ? "&aClick to vote" : ""), (p, c) -> {
                if (module.electionOpen()) {
                    openVote(p, gangId, 1);
                } else {
                    ctx.notifications().warn(p, "Elections are not open right now.");
                }
            });
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.MAP, "&6Territories",
                    "&7View contested zones and capture status.",
                    module.isWarActive() ? "&4⚔ WAR ACTIVE - rewards on capture!" : "&7War: inactive",
                    "&7Controlled: &f" + manager.territoriesControlled(gangId)), (p, c) -> openTerritories(p));
            builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.CHEST, "&6Gang Bank",
                    "&7Balance: &a$" + String.format("%.2f", manager.bankBalance(gangId)),
                    isLeader ? "&7Deposit/withdraw for your gang" : "&7Leader manages the bank"), (p, c) -> openBank(p));
            builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.EMERALD_BLOCK, "&aMembers & " + manager.rankName(uuid, gangId),
                    "&7Members: &f" + manager.memberCount(gangId),
                    "&7Your rank: &e" + manager.rankName(uuid, gangId),
                    isLeader ? "&7Click to manage members" : "&7View gang roster"), (p, c) -> openMembers(p, 1));
            builder.item(GuiKit.ACTION_5, GuiKit.icon(controlled ? Material.GLOWSTONE_DUST : Material.GRAY_DYE,
                    controlled ? "&aClaim weekly drugs" : "&7Drugs locked",
                    controlled
                            ? "&7Claim your " + gang.drugName + " booster"
                            : "&7Capture a territory to unlock drugs"), (p, c) -> {
                if (controlled) {
                    claimDrugs(p);
                    openDashboard(p);
                } else {
                    ctx.notifications().warn(p, "Your gang controls no territory.");
                }
            });

            // Gang color item
            String colorName = "north".equals(gangId) ? "LIGHT_BLUE_CONCRETE" : "MAGENTA_CONCRETE";
            Material colorMat = Material.matchMaterial(colorName);
            if (colorMat != null) {
                builder.item(8, ItemBuilder.of(colorMat).name(" ").build(), GuiKit.NONE);
            }
        }

        builder.item(GuiKit.ACTION_5 + 2, GuiKit.money(ctx.economy().balance(uuid)), GuiKit.NONE);

        // Show member heads in grid
        if (gang != null) {
            List<UUID> members = manager.membersOf(gangId);
            int shown = Math.min(members.size(), PER_PAGE);
            for (int i = 0; i < shown; i++) {
                UUID member = members.get(i);
                boolean isLeader = member.equals(leader);
                boolean isLt = manager.isLieutenant(member, gangId);
                int slot = GuiKit.GRID_FIRST + i;
                String rankPrefix = isLeader ? "&6★ " : isLt ? "&e◆ " : "";
                String rankSuffix = isLeader ? " &6Leader" : isLt ? " &eLieutenant" : "";
                int kills = manager.killsAllTime(gangId, member);
                builder.item(slot, GuiKit.playerHead(nameOf(member),
                        rankPrefix + nameOf(member) + rankSuffix,
                        "&7Kills (all time): &f" + kills,
                        "&7Kills (term): &f" + manager.killsThisTerm(gangId, member)), GuiKit.NONE);
            }
            if (members.size() > shown) {
                builder.item(GuiKit.GRID_FIRST + PER_PAGE - 1, GuiKit.icon(Material.PAPER,
                        "&7+" + (members.size() - shown) + " more members",
                        "&7Use /gang members for the full list."), GuiKit.NONE);
            }
        }

        builder.open(player);
    }

    // ========================= JOIN =========================

    private void openJoin(Player player) {
        List<Gang> gangs = manager.all();
        int pages = GuiKit.Pages.pages(gangs.size(), PER_PAGE);
        int page = Math.min(joinPage.getOrDefault(player.getUniqueId(), 1), pages);

        var builder = ctx.gui().builder(GuiKit.title("Pick a faction"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the gang menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.EMERALD, "&aJoin a faction",
                "&7North Side or South Side - choose your crew."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        List<Gang> slice = GuiKit.Pages.slice(gangs, page, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Gang gang = slice.get(i);
            boolean canJoin = manager.canJoin(gang.id);
            Material mat = Material.matchMaterial(gang.drugMaterial);
            int diff = manager.memberDiff();
            ItemStack item = ItemBuilder.of(mat == null ? Material.SUGAR : mat)
                    .name("&e" + gang.name)
                    .lore(
                            "&7Members: &f" + manager.memberCount(gang.id),
                            "&7Territories: &f" + manager.territoriesControlled(gang.id),
                            "&7Bank: &a$" + String.format("%.2f", manager.bankBalance(gang.id)),
                            canJoin ? "&aClick to join" : "&cToo many more members (diff: " + diff + ")")
                    .build();
            int slot = GuiKit.GRID_FIRST + i;
            builder.item(slot, item, (p, c) -> {
                if (!canJoin) {
                    ctx.notifications().warn(p, "That faction has too many more members. Max diff is 4.");
                    return;
                }
                manager.join(p.getUniqueId(), gang.id);
                ctx.notifications().msg(p, "&aYou joined " + gang.name + ".");
                module.publishJoin(gang.id, p.getName());
                openDashboard(p);
            });
        }

        if (pages > 1) {
            builder.item(GuiKit.PAGE_PREV, page > 1 ? GuiKit.prevPage(page) : GuiKit.pageGap(),
                    (p, c) -> { if (page > 1) { joinPage.put(p.getUniqueId(), page - 1); openJoin(p); } });
            builder.item(GuiKit.PAGE_INDICATOR, GuiKit.pageIndicator(page, pages), GuiKit.NONE);
            builder.item(GuiKit.PAGE_NEXT, page < pages ? GuiKit.nextPage(page) : GuiKit.pageGap(),
                    (p, c) -> { if (page < pages) { joinPage.put(p.getUniqueId(), page + 1); openJoin(p); } });
        }
        builder.open(player);
    }

    // ========================= VOTE =========================

    public void openVote(Player player, String gangId, int page) {
        Gang gang = manager.gang(gangId);
        List<UUID> members = manager.topKillers(gangId);
        int pages = GuiKit.Pages.pages(members.size(), PER_PAGE);
        int safe = Math.min(page, pages);

        var builder = ctx.gui().builder(GuiKit.title("Vote - " + (gang != null ? gang.name : gangId)), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the gang menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.GOLD_INGOT, "&6Biweekly leader vote",
                "&7Candidates shown by kills this term.",
                "&7Click a candidate to vote for them."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        List<UUID> slice = GuiKit.Pages.slice(members, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            UUID member = slice.get(i);
            int kills = manager.killsThisTerm(gangId, member);
            int votes = manager.voteCount(gangId, member);
            int slot = GuiKit.GRID_FIRST + i;
            builder.item(slot, GuiKit.playerHead(nameOf(member),
                    "&eVote for &f" + nameOf(member),
                    "&7Kills this term: &f" + kills,
                    "&7Votes received: &f" + votes,
                    "&7Click to cast your vote"), (p, c) -> {
                Player candidate = Bukkit.getPlayer(member);
                if (candidate == null && manager.leaderOf(gangId) == null) {
                    ctx.notifications().warn(p, "That player is offline.");
                    return;
                }
                manager.vote(gangId, p.getUniqueId(), member);
                int newVotes = manager.voteCount(gangId, member);
                ctx.notifications().msg(p, "&aVote for " + nameOf(member) + " recorded. (" + newVotes + " total)");
                if (candidate != null) {
                    ctx.notifications().msg(candidate, "&eYou received a vote from " + p.getName() + ". (" + newVotes + " total)");
                }
                p.closeInventory();
            });
        }

        if (pages > 1) {
            builder.item(GuiKit.PAGE_PREV, safe > 1 ? GuiKit.prevPage(safe) : GuiKit.pageGap(),
                    (p, c) -> { if (safe > 1) openVote(p, gangId, safe - 1); });
            builder.item(GuiKit.PAGE_INDICATOR, GuiKit.pageIndicator(safe, pages), GuiKit.NONE);
            builder.item(GuiKit.PAGE_NEXT, safe < pages ? GuiKit.nextPage(safe) : GuiKit.pageGap(),
                    (p, c) -> { if (safe < pages) openVote(p, gangId, safe + 1); });
        }
        builder.open(player);
    }

    // ========================= BANK =========================

    public void openBank(Player player) {
        UUID uuid = player.getUniqueId();
        String gangId = manager.gangOf(uuid);
        if (gangId == null) {
            ctx.notifications().warn(player, "You are not in a gang.");
            return;
        }
        Gang gang = manager.gang(gangId);
        boolean isLeader = manager.isLeader(uuid, gangId);
        double gangBal = manager.bankBalance(gangId);
        double playerBal = ctx.economy().balance(uuid);

        var builder = ctx.gui().builder(GuiKit.title((gang != null ? gang.name : "") + " Bank"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the gang menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.CHEST, "&6Gang Bank",
                "&7Balance: &a$" + String.format("%.2f", gangBal),
                isLeader ? "&7You can deposit and withdraw" : "&7Only the leader can manage funds"), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(19, GuiKit.icon(Material.GOLD_INGOT, "&aDeposit money",
                "&7Your balance: &f$" + String.format("%.2f", playerBal),
                "&7Click to deposit $100"),
                (p, c) -> {
                    if (ctx.economy().withdraw(uuid, 100, "gang-bank-deposit").success()) {
                        manager.bankDeposit(gangId, 100);
                        ctx.notifications().msg(p, "&aDeposited $100 to gang bank.");
                        openBank(p);
                    } else {
                        ctx.notifications().warn(p, "Not enough money.");
                    }
                });

        builder.item(21, GuiKit.icon(Material.GOLD_BLOCK, "&aDeposit $500",
                "&7Your balance: &f$" + String.format("%.2f", playerBal)),
                (p, c) -> {
                    if (ctx.economy().withdraw(uuid, 500, "gang-bank-deposit").success()) {
                        manager.bankDeposit(gangId, 500);
                        ctx.notifications().msg(p, "&aDeposited $500 to gang bank.");
                        openBank(p);
                    } else {
                        ctx.notifications().warn(p, "Not enough money.");
                    }
                });

        builder.item(23, isLeader ? GuiKit.icon(Material.REDSTONE, "&cWithdraw $100",
                "&7Gang balance: &f$" + String.format("%.2f", gangBal)) : GuiKit.icon(Material.BARRIER, "&cWithdraw",
                "&7Only the leader can withdraw"), (p, c) -> {
            if (!isLeader) {
                ctx.notifications().warn(p, "Only the leader can withdraw from the bank.");
                return;
            }
            if (manager.bankWithdraw(gangId, 100)) {
                ctx.economy().deposit(uuid, 100, "gang-bank-withdraw");
                ctx.notifications().msg(p, "&cWithdrew $100 from gang bank.");
                openBank(p);
            } else {
                ctx.notifications().warn(p, "Not enough in the gang bank.");
            }
        });

        builder.item(25, isLeader ? GuiKit.icon(Material.REDSTONE_BLOCK, "&cWithdraw $500",
                "&7Gang balance: &f$" + String.format("%.2f", gangBal)) : GuiKit.icon(Material.BARRIER, "&cWithdraw",
                "&7Only the leader can withdraw"), (p, c) -> {
            if (!isLeader) {
                ctx.notifications().warn(p, "Only the leader can withdraw from the bank.");
                return;
            }
            if (manager.bankWithdraw(gangId, 500)) {
                ctx.economy().deposit(uuid, 500, "gang-bank-withdraw");
                ctx.notifications().msg(p, "&cWithdrew $500 from gang bank.");
                openBank(p);
            } else {
                ctx.notifications().warn(p, "Not enough in the gang bank.");
            }
        });

        builder.item(40, GuiKit.money(playerBal), GuiKit.NONE);

        builder.open(player);
    }

    // ========================= MEMBERS =========================

    public void openMembers(Player player, int page) {
        UUID uuid = player.getUniqueId();
        String gangId = manager.gangOf(uuid);
        if (gangId == null) return;
        Gang gang = manager.gang(gangId);
        boolean isLeader = manager.isLeader(uuid, gangId);
        List<UUID> members = manager.membersOf(gangId);
        int pages = GuiKit.Pages.pages(members.size(), PER_PAGE);
        int safe = Math.min(page, pages);

        var builder = ctx.gui().builder(GuiKit.title((gang != null ? gang.name : "") + " Members"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the gang menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.PLAYER_HEAD, "&6" + (gang != null ? gang.name : "") + " Members",
                "&7Total: &f" + manager.memberCount(gangId),
                "&7Your rank: &e" + manager.rankName(uuid, gangId)), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        List<UUID> slice = GuiKit.Pages.slice(members, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            UUID member = slice.get(i);
            int slot = GuiKit.GRID_FIRST + i;
            boolean isTargetLeader = manager.isLeader(member, gangId);
            boolean isLt = manager.isLieutenant(member, gangId);
            String rankPrefix = isTargetLeader ? "&6★ " : isLt ? "&e◆ " : "";
            String rank = manager.rankName(member, gangId);

            ItemStack item;
            if (isLeader && !member.equals(uuid)) {
                item = ItemBuilder.of(Material.PLAYER_HEAD)
                        .name(rankPrefix + nameOf(member))
                        .lore("&7Rank: &f" + rank,
                                "&7Kills (all time): &f" + manager.killsAllTime(gangId, member),
                                "&7Kills (term): &f" + manager.killsThisTerm(gangId, member),
                                "&eClick: promote/demote")
                        .build();
                builder.item(slot, item, (p, c) -> {
                    if (isLt) {
                        manager.demoteFromLieutenant(gangId, member);
                        ctx.notifications().msg(p, "&cDemoted " + nameOf(member) + " from lieutenant.");
                    } else {
                        manager.promoteToLieutenant(gangId, member);
                        ctx.notifications().msg(p, "&aPromoted " + nameOf(member) + " to lieutenant.");
                    }
                    openMembers(p, safe);
                });
            } else {
                item = GuiKit.playerHead(nameOf(member),
                        rankPrefix + nameOf(member),
                        "&7Rank: &f" + rank,
                        "&7Kills (all time): &f" + manager.killsAllTime(gangId, member),
                        "&7Kills (term): &f" + manager.killsThisTerm(gangId, member));
                builder.item(slot, item, GuiKit.NONE);
            }
        }

        if (pages > 1) {
            builder.item(GuiKit.PAGE_PREV, safe > 1 ? GuiKit.prevPage(safe) : GuiKit.pageGap(),
                    (p, c) -> { if (safe > 1) openMembers(p, safe - 1); });
            builder.item(GuiKit.PAGE_INDICATOR, GuiKit.pageIndicator(safe, pages), GuiKit.NONE);
            builder.item(GuiKit.PAGE_NEXT, safe < pages ? GuiKit.nextPage(safe) : GuiKit.pageGap(),
                    (p, c) -> { if (safe < pages) openMembers(p, safe + 1); });
        }
        builder.open(player);
    }

    // ========================= TERRITORIES =========================

    public void openTerritories(Player player) {
        List<Territory> territories = manager.territories();
        int pages = GuiKit.Pages.pages(territories.size(), PER_PAGE);
        int page = Math.min(territoryPage.getOrDefault(player.getUniqueId(), 1), pages);

        var builder = ctx.gui().builder(GuiKit.title("Contested territories"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the gang menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.MAP, "&6Territory war",
                module.isWarActive()
                        ? "&4⚔ WAR ACTIVE - capture for rewards!"
                        : "&7Stand inside a zone with more gang",
                module.isWarActive()
                        ? "&7members than the enemy to capture."
                        : "&7members to capture it.",
                "&7Click a zone to teleport there."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        List<Territory> slice = GuiKit.Pages.slice(territories, page, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Territory t = slice.get(i);
            String ownerName = t.owner.isEmpty() ? "&7Neutral" : "&f" + manager.gang(t.owner).name;
            String progressLine = progressLine(t);
            Material mat = t.owner.isEmpty() ? Material.LIGHT_GRAY_CONCRETE
                    : Material.matchMaterial(gangColor(t.owner));

            var lore = new java.util.ArrayList<String>();
            lore.add("&7Owner: " + ownerName);
            lore.add(progressLine);
            lore.add("&7Capture time: &f" + t.captureSeconds + "s");
            if (t.rewardMoney > 0) lore.add("&7War reward: &a$" + (int) t.rewardMoney);
            if (t.rewardGuns > 0) lore.add("&7War reward: &c" + t.rewardGuns + "x weapons");
            lore.add("&aClick to teleport");

            ItemStack item = ItemBuilder.of(mat == null ? Material.LIGHT_GRAY_CONCRETE : mat)
                    .name(t.name)
                    .lore(lore.toArray(new String[0]))
                    .build();
            int slot = GuiKit.GRID_FIRST + i;
            builder.item(slot, item, (p, c) -> teleportToTerritory(p, t));
        }

        if (pages > 1) {
            builder.item(GuiKit.PAGE_PREV, page > 1 ? GuiKit.prevPage(page) : GuiKit.pageGap(),
                    (p, c) -> { if (page > 1) { territoryPage.put(p.getUniqueId(), page - 1); openTerritories(p); } });
            builder.item(GuiKit.PAGE_INDICATOR, GuiKit.pageIndicator(page, pages), GuiKit.NONE);
            builder.item(GuiKit.PAGE_NEXT, page < pages ? GuiKit.nextPage(page) : GuiKit.pageGap(),
                    (p, c) -> { if (page < pages) { territoryPage.put(p.getUniqueId(), page + 1); openTerritories(p); } });
        }
        builder.open(player);
    }

    // ========================= HELPERS =========================

    private String progressLine(Territory t) {
        if (t.progressOwner.isEmpty()) return "&7Standstill";
        Gang g = manager.gang(t.progressOwner);
        String gName = g == null ? t.progressOwner : g.name;
        return "&7Capturing: &f" + gName + " &b" + t.progress + "%";
    }

    private String gangColor(String gangId) {
        if ("north".equals(gangId)) return "LIGHT_BLUE_CONCRETE";
        if ("south".equals(gangId)) return "MAGENTA_CONCRETE";
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
            ctx.notifications().warn(player, "Your gang controls no territory.");
            return false;
        }
        Gang gang = manager.gang(gangId);
        if (gang == null) return false;
        int claim = manager.claimDrug(player.getUniqueId(), module.weeklyDrugs());
        if (claim == -1) {
            ctx.notifications().warn(player, "You already claimed your drugs this week.");
            return false;
        }
        Material material = Material.matchMaterial(gang.drugMaterial);
        if (material == null) material = Material.SUGAR;
        ItemStack drug = ItemBuilder.of(material)
                .name(gang.drugName)
                .lore("&7Gang booster: &f" + gang.drugEffect + " " + gang.drugAmplifier,
                        "&7Claimed " + claim + "/" + module.weeklyDrugs() + " this week")
                .tag(GangModule.drugKey(), gangId)
                .build();
        var leftover = player.getInventory().addItem(drug);
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
        }
        ctx.notifications().msg(player, "&aClaimed " + gang.drugName + " (" + claim + "/" + module.weeklyDrugs() + ").");
        return true;
    }

    private String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }
}
