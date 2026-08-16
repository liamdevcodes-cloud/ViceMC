package net.vicemc.modules.government;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.modules.business.Business;
import net.vicemc.modules.business.BusinessManager;
import net.vicemc.modules.business.BusinessType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The government menu: business licensing, goods subsidies, asset seizures,
 * business bans and pardons - all in the dark modern menu style.
 */
public final class GovernmentGui {

    private static final int PER_PAGE = 27;
    private static final String[] BAN_REASONS = {"government action", "abuse", "fraud", "RMT", "evasion"};

    private final ViceModuleContext ctx;
    private final GovernmentModule module;
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();

    public GovernmentGui(ViceModuleContext ctx, GovernmentModule module) {
        this.ctx = ctx;
        this.module = module;
    }

    public void openDashboard(Player player) {
        BusinessManager manager = module.businessModule().manager();
        var builder = ctx.gui().builder(GuiKit.title("Government"), 6);
        GuiKit.frame(builder);
        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&6How it works",
                "&7The government regulates the economy:",
                "&7licenses, subsidies, asset seizures,",
                "&7business bans and pardons."), GuiKit.NONE);
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.GOLD_BLOCK)
                .name("&6Government")
                .lore("&7Registered businesses: &f" + manager.all().size(),
                        "&7Licenses issued: &f" + countKeys("license:"),
                        "&7Business bans: &f" + countKeys("ban:"))
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.BOOK, "&6Licenses",
                "&7Issue or revoke business",
                "&7licenses for a player."), (p, c) -> openLicensePicker(p, 1));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.GOLD_INGOT, "&6Subsidies",
                "&7Grant goods to a business",
                "&7as government support."), (p, c) -> openSubsidyPicker(p, 1));
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.RED_WOOL, "&cBusiness ban",
                "&7Ban a player from starting",
                "&7new businesses."), (p, c) -> openBanPicker(p, 1));
        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.LIME_DYE, "&aPardon",
                "&7Lift a business ban."), (p, c) -> openPardonPicker(p, 1));
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.REDSTONE, "&cSeize assets",
                "&7Withdraw money from a player",
                "&7for unpaid debts."), (p, c) -> openSeizePicker(p, 1));

        builder.open(player);
    }

    // --- Licenses ---------------------------------------------------------

    private void openLicensePicker(Player player, int page) {
        openPlayerPicker(player, "Pick a player", page,
                "&6Licenses", Material.BOOK,
                "&7Click a player to manage their",
                "&7business licenses.",
                () -> openDashboard(player),
                target -> openLicenses(player, target));
    }

    private void openLicenses(Player player, UUID target) {
        BusinessManager manager = module.businessModule().manager();
        var builder = ctx.gui().builder(GuiKit.title("Licenses of " + nameOf(target)), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the player picker"), (p, c) -> openLicensePicker(p, 1));
        builder.item(GuiKit.STATUS, GuiKit.playerHead(nameOf(target),
                "&e" + nameOf(target),
                "&7Licenses: &f" + String.join(", ", manager.licenses(target)),
                "&7Click a type to grant or revoke."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        BusinessType[] types = BusinessType.values();
        for (int i = 0; i < types.length; i++) {
            BusinessType type = types[i];
            boolean has = manager.licenses(target).contains(type.name());
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(has ? Material.LIME_DYE : Material.GRAY_DYE)
                    .name((has ? "&a" : "&7") + type.display())
                    .lore(has ? "&7Licensed. &eClick to revoke."
                            : "&7Not licensed. &aClick to grant.")
                    .build(), (p, c) -> {
                if (has) {
                    module.revokeLicense(p, target, type.name());
                } else {
                    module.issueLicense(p, target, type.name());
                }
                openLicenses(p, target);
            });
        }
        builder.open(player);
    }

    // --- Subsidies --------------------------------------------------------

    private void openSubsidyPicker(Player player, int page) {
        List<Business> businesses = new ArrayList<>(module.businessModule().manager().all());
        int pages = GuiKit.Pages.pages(businesses.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Pick a business"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the government menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.GOLD_INGOT, "&6Subsidies",
                "&7" + businesses.size() + " business(es).",
                "&7Click a business to subsidize."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Business> slice = GuiKit.Pages.slice(businesses, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Business business = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.CHEST)
                    .name("&e" + business.name)
                    .lore("&7Type: &f" + businessTypeDisplay(business),
                            "&7Owner: &f" + nameOf(business.owner),
                            "&7ID: &f#" + business.id,
                            "&aClick to subsidize.")
                    .build(), (p, c) -> openSubsidy(p, business.id));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openSubsidyPicker(p, next));
        builder.open(player);
    }

    private void openSubsidy(Player player, int businessId) {
        Business business = module.businessModule().manager().byId(businessId).orElse(null);
        if (business == null) {
            ctx.notifications().warn(player, "That business no longer exists.");
            openSubsidyPicker(player, 1);
            return;
        }
        Draft draft = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        draft.amount = Math.max(module.govConfig().getInt("subsidy-min", 20000), draft.amount);

        var builder = ctx.gui().builder(GuiKit.title("Subsidy: " + business.name), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the business picker"), (p, c) -> openSubsidyPicker(p, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.CHEST)
                .name("&e" + business.name)
                .lore("&7Owner: &f" + nameOf(business.owner),
                        "&7Type: &f" + businessTypeDisplay(business),
                        "&7Goods are granted to the owner",
                        "&7while they are online.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, ItemBuilder.of(Material.GOLD_INGOT)
                .name("&6Subsidy value: &f" + GuiKit.fmt(draft.amount))
                .lore("&7Left: &f+5,000", "&7Right: &f-5,000",
                        "&7Shift-left: &f+25,000", "&7Shift-right: &f-25,000",
                        "&7Min: &f" + GuiKit.fmt(module.govConfig().getInt("subsidy-min", 20000)),
                        "&7Max: &f" + GuiKit.fmt(module.govConfig().getInt("subsidy-max", 40000)))
                .build(), (p, c) -> {
            int step = c.isShiftClick() ? 25_000 : 5_000;
            int delta = (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) ? -step : step;
            draft.amount = Math.max(module.govConfig().getInt("subsidy-min", 20000),
                    Math.min(module.govConfig().getInt("subsidy-max", 40000), draft.amount + delta));
            openSubsidy(p, businessId);
        });
        builder.item(GuiKit.ACTION_3, GuiKit.cta(Material.GREEN_WOOL, "&aApprove subsidy",
                "&7Grant goods worth",
                "&7" + GuiKit.fmt(draft.amount) + " to this business."), (p, c) -> {
            module.grantSubsidy(p, businessId, draft.amount);
            drafts.remove(p.getUniqueId());
            openDashboard(p);
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to the business picker."), (p, c) -> openSubsidyPicker(p, 1));
        builder.open(player);
    }

    // --- Business ban -----------------------------------------------------

    private void openBanPicker(Player player, int page) {
        openPlayerPicker(player, "Pick a player", page,
                "&cBusiness ban", Material.RED_WOOL,
                "&7Click a player to ban from",
                "&7starting businesses.",
                () -> openDashboard(player),
                target -> openBanReason(player, target));
    }

    private void openBanReason(Player player, UUID target) {
        var builder = ctx.gui().builder(GuiKit.title("Ban " + nameOf(target)), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the player picker"), (p, c) -> openBanPicker(p, 1));
        builder.item(GuiKit.STATUS, GuiKit.playerHead(nameOf(target),
                "&e" + nameOf(target),
                "&7Balance: &f" + GuiKit.fmt(ctx.economy().balance(target)),
                "&7Pick a reason for the ban."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        for (int i = 0; i < BAN_REASONS.length; i++) {
            String reason = BAN_REASONS[i];
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.RED_DYE)
                    .name("&c" + reason)
                    .lore("&7Click to ban for this reason.").build(), (p, c) -> {
                module.businessBan(p, target, reason);
                openDashboard(p);
            });
        }
        builder.open(player);
    }

    // --- Pardon -----------------------------------------------------------

    private void openPardonPicker(Player player, int page) {
        openPlayerPicker(player, "Pick a player", page,
                "&aPardon", Material.LIME_DYE,
                "&7Click a player to pardon and",
                "&7lift their business ban.",
                () -> openDashboard(player),
                target -> openPardonConfirm(player, target));
    }

    private void openPardonConfirm(Player player, UUID target) {
        var builder = ctx.gui().builder(GuiKit.title("Pardon " + nameOf(target)), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the player picker"), (p, c) -> openPardonPicker(p, 1));
        boolean banned = module.businessModule().manager().isBusinessBanned(target);
        builder.item(GuiKit.STATUS, GuiKit.playerHead(nameOf(target),
                "&e" + nameOf(target),
                banned ? "&cBanned: &f" + module.businessModule().manager().banReason(target)
                        : "&aNot banned",
                banned ? "&7Click to lift the ban." : "&7Nothing to pardon."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        if (banned) {
            builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.LIME_WOOL, "&aPardon",
                    "&7Allow " + nameOf(target) + " to",
                    "&7start businesses again."), (p, c) -> {
                module.pardon(p, target);
                openDashboard(p);
            });
        }
        builder.open(player);
    }

    // --- Seize ------------------------------------------------------------

    private void openSeizePicker(Player player, int page) {
        openPlayerPicker(player, "Pick a player", page,
                "&cSeize assets", Material.REDSTONE,
                "&7Click a player to withdraw",
                "&7money for unpaid debts.",
                () -> openDashboard(player),
                target -> openSeize(player, target));
    }

    private void openSeize(Player player, UUID target) {
        Draft draft = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        var builder = ctx.gui().builder(GuiKit.title("Seize from " + nameOf(target)), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the player picker"), (p, c) -> openSeizePicker(p, 1));
        builder.item(GuiKit.STATUS, GuiKit.playerHead(nameOf(target),
                "&e" + nameOf(target),
                "&7Balance: &f" + GuiKit.fmt(ctx.economy().balance(target))), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, ItemBuilder.of(Material.REDSTONE)
                .name("&cSeizure amount: &f" + GuiKit.fmt(draft.amount))
                .lore("&7Left: &f+5,000", "&7Right: &f-5,000",
                        "&7Shift-left: &f+50,000", "&7Shift-right: &f-50,000")
                .build(), (p, c) -> {
            int step = c.isShiftClick() ? 50_000 : 5_000;
            int delta = (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) ? -step : step;
            draft.amount = Math.max(0, Math.min(5_000_000, draft.amount + delta));
            openSeize(p, target);
        });
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.RED_WOOL, "&cSeize " + GuiKit.fmt(draft.amount),
                "&7Withdraw this amount from",
                "&7" + nameOf(target) + "."), (p, c) -> {
            module.seize(p, target, draft.amount);
            drafts.remove(p.getUniqueId());
            openDashboard(p);
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to the player picker."), (p, c) -> openSeizePicker(p, 1));
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
        builder.item(GuiKit.STATUS, GuiKit.icon(icon, name,
                descLine1, descLine2), GuiKit.NONE);
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

    private int countKeys(String prefix) {
        int count = 0;
        for (String key : ctx.storage().moduleDataAll("business").keySet()) {
            if (key.startsWith(prefix)) {
                count++;
            }
        }
        return count;
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

    private String businessTypeDisplay(Business business) {
        return business.typeEnum() == null ? business.type : business.typeEnum().display();
    }

    private static final class Draft {
        double amount = 20_000;
    }
}
