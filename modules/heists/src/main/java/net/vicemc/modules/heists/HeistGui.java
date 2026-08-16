package net.vicemc.modules.heists;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Family and heist menu: family management (invite, join, kick, members),
 * heist site browsing with risk/reward, and live heist control (abort,
 * return by helicopter) - all in the dark modern menu style.
 */
public final class HeistGui {

    private static final int PER_PAGE = 27;

    private final ViceModuleContext ctx;
    private final HeistModule module;

    public HeistGui(ViceModuleContext ctx, HeistModule module) {
        this.ctx = ctx;
        this.module = module;
    }

    public void openDashboard(Player player) {
        Family family = module.families().byMember(player.getUniqueId());
        boolean owner = family != null && family.owner.equals(player.getUniqueId());
        boolean active = module.heists().activeFor(player.getUniqueId()).isPresent();

        var builder = ctx.gui().builder(GuiKit.title("Family"), 6);
        GuiKit.frame(builder);
        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&6How it works",
                "&7Register a family, recruit members",
                "&7and run coordinated heists. At least",
                "&7two members must be online to start.",
                "&7Escape via the getaway boat."), GuiKit.NONE);
        if (family == null) {
            builder.item(GuiKit.STATUS, GuiKit.icon(Material.GRAY_DYE, "&7Not in a family",
                    "&7Register one for &f" + GuiKit.fmt(module.heistConfig().getDouble("registration-fee", 150000)) + "&7.",
                    "&7Active heists: &f" + module.heists().activeCount()), GuiKit.NONE);
        } else {
            builder.item(GuiKit.STATUS, ItemBuilder.of(Material.PLAYER_HEAD)
                    .name("&6" + family.name)
                    .lore("&7ID: &f#" + family.id,
                            "&7Owner: &f" + nameOf(family.owner),
                            "&7Members: &f" + family.members.size(),
                            "&7Active heists: &f" + module.heists().activeCount())
                    .build(), GuiKit.NONE);
        }
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.CREEPER_HEAD, "&6Heist sites",
                "&7Browse locations, risk and",
                "&7payouts. Start from inside a site."), (p, c) -> openSites(p, 1));

        if (family != null) {
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.PLAYER_HEAD, "&eMembers",
                    "&7View everyone in the family."), (p, c) -> openMembers(p, 1));
            if (owner) {
                builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.NAME_TAG, "&aInvite",
                        "&7Send a join invite to an",
                        "&7online player."), (p, c) -> openInvitePicker(p, 1));
                builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.RED_DYE, "&cKick",
                        "&7Remove a member from",
                        "&7the family."), (p, c) -> openKickPicker(p, 1));
            } else {
                builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.GRAY_DYE, "&7Member",
                        "&7Only the owner can invite",
                        "&7and kick members."), GuiKit.NONE);
            }
            builder.item(GuiKit.ACTION_5, GuiKit.icon(owner ? Material.RED_WOOL : Material.REDSTONE,
                    "&c" + (owner ? "Disband family" : "Leave family"),
                    "&7" + (owner ? "Removes the family and all its members."
                            : "You will become unaffiliated.")), (p, c) -> openLeaveConfirm(p));
        } else {
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.GOLD_INGOT, "&6Create family",
                "&7Cost: &f" + GuiKit.fmt(module.heistConfig().getDouble("registration-fee", 150000)),
                "&7Requires owning a home.",
                "&7Naming happens in chat."), (p, c) -> openCreateConfirm(p));
            builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.LIME_DYE, "&aJoin a family",
                    "&7Accept an invite you have",
                    "&7received."), (p, c) -> openJoinPicker(p, 1));
        }

        if (active) {
            builder.item(GuiKit.GRID_FIRST, GuiKit.icon(Material.REDSTONE_TORCH, "&cMy active heist",
                    "&7Abort the run or take the",
                    "&7helicopter back."), (p, c) -> openMyHeist(p));
        }
        builder.open(player);
    }

    // --- Family: create ---------------------------------------------------

    private void openCreateConfirm(Player player) {
        double fee = module.heistConfig().getDouble("registration-fee", 150000);
        var builder = ctx.gui().builder(GuiKit.title("Create a family"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the family menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.GOLD_INGOT)
                .name("&6Create a family")
                .lore("&7Registration costs &f" + GuiKit.fmt(fee) + "&7.",
                        "&7Requires owning a home.",
                        "&7Naming happens in chat - this",
                        "&7menu cannot type text.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.GREEN_WOOL, "&aName it in chat",
                "&7Run: &f/family create <name>",
                "&7Your balance will be charged."), (p, c) -> {
            p.closeInventory();
            p.sendMessage(Text.color("&eRegister your family: &f/family create <name>"
                    + " &e(fee &f" + GuiKit.fmt(fee) + "&e)."));
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to the family menu."), (p, c) -> openDashboard(p));
        builder.open(player);
    }

    // --- Family: invite / join / kick / members / leave -------------------

    private void openInvitePicker(Player player, int page) {
        openPlayerPicker(player, "Invite a player", page,
                "&aInvite", Material.NAME_TAG,
                "&7Click a player to send a",
                "&7join invite.",
                () -> openDashboard(player),
                target -> {
                    module.inviteToFamily(player, target);
                    openDashboard(player);
                });
    }

    private void openKickPicker(Player player, int page) {
        Family family = module.families().byMember(player.getUniqueId());
        List<UUID> members = new ArrayList<>();
        if (family != null) {
            members.addAll(family.members);
            members.remove(player.getUniqueId());
            members.remove(family.owner);
        }
        int pages = GuiKit.Pages.pages(members.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Kick a member"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the family menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.RED_DYE, "&cKick",
                "&7" + members.size() + " kickable member(s)."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<UUID> slice = GuiKit.Pages.slice(members, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            UUID target = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.playerHead(nameOf(target),
                    "&e" + nameOf(target),
                    "&cClick to remove."), (p, c) -> {
                module.kickMember(p, target);
                openDashboard(p);
            });
        }
        addPaging(builder, player, safe, pages, (p, next) -> openKickPicker(p, next));
        builder.open(player);
    }

    private void openJoinPicker(Player player, int page) {
        UUID me = player.getUniqueId();
        List<Family> invites = new ArrayList<>();
        for (Family family : module.families().all()) {
            if (module.families().invites(family.id).contains(me)) {
                invites.add(family);
            }
        }
        int pages = GuiKit.Pages.pages(invites.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Join a family"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the family menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.LIME_DYE, "&aYour invites",
                "&7" + invites.size() + " family invite(s).",
                "&7Click one to join."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        if (invites.isEmpty()) {
            builder.item(GuiKit.GRID_FIRST, GuiKit.icon(Material.GRAY_DYE, "&7No invites",
                    "&7Ask a family owner to invite you."), GuiKit.NONE);
        }
        List<Family> slice = GuiKit.Pages.slice(invites, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Family family = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.PLAYER_HEAD)
                    .name("&e" + family.name)
                    .lore("&7ID: &f#" + family.id,
                            "&7Owner: &f" + nameOf(family.owner),
                            "&7Members: &f" + family.members.size(),
                            "&aClick to join.")
                    .build(), (p, c) -> {
                module.joinFamily(p, family.id);
                openDashboard(p);
            });
        }
        addPaging(builder, player, safe, pages, (p, next) -> openJoinPicker(p, next));
        builder.open(player);
    }

    private void openMembers(Player player, int page) {
        Family family = module.families().byMember(player.getUniqueId());
        List<UUID> members = family == null ? List.of() : family.members;
        int pages = GuiKit.Pages.pages(members.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Family members"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the family menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.PLAYER_HEAD,
                family == null ? "&7No family" : "&6" + family.name,
                "&7" + members.size() + " member(s)."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<UUID> slice = GuiKit.Pages.slice(members, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            UUID member = slice.get(i);
            boolean owner = family != null && family.owner.equals(member);
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.playerHead(nameOf(member),
                    (owner ? "&6\u2605 " : "&e") + nameOf(member),
                    owner ? "&7Family owner" : "&7Member"), GuiKit.NONE);
        }
        addPaging(builder, player, safe, pages, (p, next) -> openMembers(p, next));
        builder.open(player);
    }

    private void openLeaveConfirm(Player player) {
        Family family = module.families().byMember(player.getUniqueId());
        boolean owner = family != null && family.owner.equals(player.getUniqueId());
        var builder = ctx.gui().builder(GuiKit.title(owner ? "Disband family" : "Leave family"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the family menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(owner ? Material.RED_WOOL : Material.REDSTONE,
                owner ? "&cDisband this family?" : "&cLeave this family?",
                owner ? "&7The family is removed and all members become unaffiliated."
                        : "&7You will become unaffiliated."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.RED_WOOL, "&cConfirm",
                "&7" + (owner ? "Disband the family." : "Leave the family.")), (p, c) -> {
            module.leaveFamily(p);
            openDashboard(p);
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to the family menu."), (p, c) -> openDashboard(p));
        builder.open(player);
    }

    // --- Heists -----------------------------------------------------------

    public void openSites(Player player, int page) {
        List<HeistSite> sites = new ArrayList<>(module.heists().sites());
        int pages = GuiKit.Pages.pages(sites.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Heist sites"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the family menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.CREEPER_HEAD, "&6Heist sites",
                "&7" + sites.size() + " location(s).",
                "&7Risk - payout - duration.",
                "&7Active heists: &f" + module.heists().activeCount()), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<HeistSite> slice = GuiKit.Pages.slice(sites, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            HeistSite site = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(site.risk >= 3 ? Material.RED_CONCRETE
                    : site.risk == 2 ? Material.YELLOW_CONCRETE : Material.LIME_CONCRETE)
                    .name("&e" + site.name)
                    .lore(riskStars(site.risk),
                            "&7Payout: &f" + GuiKit.fmt(site.payout),
                            "&7Duration: &f" + (site.durationSeconds / 60) + " min",
                            "&aClick to view.")
                    .build(), (p, c) -> openSiteConfirm(p, site.id));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openSites(p, next));
        builder.open(player);
    }

    private void openSiteConfirm(Player player, String siteId) {
        HeistSite site = module.heists().site(siteId);
        if (site == null) {
            ctx.notifications().warn(player, "That heist site no longer exists.");
            openSites(player, 1);
            return;
        }
        Family family = module.families().byMember(player.getUniqueId());
        int crewOnline = 0;
        if (family != null) {
            for (UUID member : family.members) {
                if (Bukkit.getPlayer(member) != null) {
                    crewOnline++;
                }
            }
        }
        boolean inRegion = ctx.regions().isInside(player.getLocation(), site.regionTag);

        var builder = ctx.gui().builder(GuiKit.title(site.name), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the heist sites"), (p, c) -> openSites(p, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.CREEPER_HEAD)
                .name("&6" + site.name)
                .lore(riskStars(site.risk),
                        "&7Payout: &f" + GuiKit.fmt(site.payout),
                        "&7Duration: &f" + (site.durationSeconds / 60) + " min",
                        family == null ? "&cYou are not in a family."
                                : "&7Crew online: &f" + crewOnline + "&7/&f" + family.members.size(),
                        inRegion ? "&aYou are inside the site."
                                : "&cYou must be standing inside the site.").build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.REDSTONE_TORCH, "&cStart the heist",
                "&7Requires 2+ family members online",
                "&7and being inside the site."), (p, c) -> {
            if (module.startHeist(p, siteId)) {
                p.closeInventory();
            }
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to the heist sites."), (p, c) -> openSites(p, 1));
        builder.open(player);
    }

    private void openMyHeist(Player player) {
        Heist heist = module.heists().activeFor(player.getUniqueId()).orElse(null);
        if (heist == null) {
            ctx.notifications().warn(player, "You have no active heist.");
            openDashboard(player);
            return;
        }
        HeistSite site = module.heists().site(heist.siteId);
        long left = Math.max(0, heist.endsAt - System.currentTimeMillis());
        boolean onIsland = ctx.regions().isInside(player.getLocation(),
                module.heistConfig().getString("island-region", "heist:island"));

        var builder = ctx.gui().builder(GuiKit.title("Active heist"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the family menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.REDSTONE_TORCH)
                .name("&c" + (site == null ? heist.siteId : site.name))
                .lore("&7Phase: &f" + heist.phase,
                        "&7Time left: &f" + formatDuration(left),
                        "&7Leader: &f" + nameOf(heist.leader),
                        "&7Crew: &f" + heist.robbers.size())
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.RED_WOOL, "&cAbort heist",
                "&7The crew is pulled out and the",
                "&7run is counted as failed."), (p, c) -> {
            module.abortHeist(p);
            openDashboard(p);
        });
        if (onIsland) {
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.ELYTRA, "&aHelicopter back",
                    "&7Choose a route to return",
                    "&7to the city."), (p, c) -> openReturnRoutes(p, 1));
        } else {
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.GRAY_DYE, "&7Helicopter",
                    "&7Take the getaway boat to the",
                    "&7island first to return by air."), GuiKit.NONE);
        }
        builder.open(player);
    }

    private void openReturnRoutes(Player player, int page) {
        List<String> routes = new ArrayList<>(module.heists().helicopterRoutes());
        int pages = GuiKit.Pages.pages(routes.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Helicopter routes"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("your active heist"), (p, c) -> openMyHeist(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.ELYTRA, "&aHelicopter routes",
                "&7" + routes.size() + " route(s)."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<String> slice = GuiKit.Pages.slice(routes, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            String route = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.FIREWORK_ROCKET)
                    .name("&e" + route)
                    .lore("&aClick to fly back to the city.").build(), (p, c) -> {
                module.returnFromIsland(p, route);
                p.closeInventory();
            });
        }
        addPaging(builder, player, safe, pages, (p, next) -> openReturnRoutes(p, next));
        builder.open(player);
    }

    // --- Player picker ----------------------------------------------------

    private void openPlayerPicker(Player player, String title, int page, String name,
                                  Material icon, String descLine1, String descLine2,
                                  Runnable back, Consumer<UUID> onPick) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.removeIf(p -> p.getUniqueId().equals(player.getUniqueId()));
        int pages = GuiKit.Pages.pages(online.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title(title), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the previous menu"), (p, c) -> back.run());
        builder.item(GuiKit.STATUS, GuiKit.icon(icon, name, descLine1, descLine2), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Player> slice = GuiKit.Pages.slice(online, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Player target = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.playerHead(target.getName(),
                    "&e" + target.getName(),
                    "&7Balance: &f" + GuiKit.fmt(ctx.economy().balance(target.getUniqueId())),
                    "&7Click to select."), (p, c) -> onPick.accept(target.getUniqueId()));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openPlayerPicker(p, title, next, name,
                icon, descLine1, descLine2, back, onPick));
        builder.open(player);
    }

    // --- Helpers ----------------------------------------------------------

    private String riskStars(int risk) {
        return "&7Risk: &f" + "\u2605".repeat(risk) + "\u2606".repeat(Math.max(0, 3 - risk));
    }

    private void addPaging(GUIService.GuiBuilder builder, Player player, int page, int pages, PagingAction action) {
        if (pages <= 1) {
            return;
        }
        builder.item(GuiKit.PAGE_PREV, page > 1 ? GuiKit.prevPage(page) : GuiKit.pageGap(),
                (p, c) -> {
                    if (page > 1) {
                        action.accept(p, page - 1);
                    }
                });
        builder.item(GuiKit.PAGE_INDICATOR, GuiKit.pageIndicator(page, pages), GuiKit.NONE);
        builder.item(GuiKit.PAGE_NEXT, page < pages ? GuiKit.nextPage(page) : GuiKit.pageGap(),
                (p, c) -> {
                    if (page < pages) {
                        action.accept(p, page + 1);
                    }
                });
    }

    @FunctionalInterface
    private interface PagingAction {
        void accept(Player player, int page);
    }

    private String nameOf(UUID uuid) {
        if (uuid == null) {
            return "unknown";
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    private String formatDuration(long millis) {
        long minutes = millis / 60_000L;
        long seconds = (millis % 60_000L) / 1000L;
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }
}
