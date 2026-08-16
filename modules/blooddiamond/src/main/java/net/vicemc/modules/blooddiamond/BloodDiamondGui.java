package net.vicemc.modules.blooddiamond;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
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

/**
 * Blood Diamond Mine menu: shard crafting, store-credit redemption and the
 * RMT flag list (admin) - all in the dark modern menu style.
 */
public final class BloodDiamondGui {

    private static final int PER_PAGE = 27;

    private final ViceModuleContext ctx;
    private final BloodDiamondModule module;
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();

    public BloodDiamondGui(ViceModuleContext ctx, BloodDiamondModule module) {
        this.ctx = ctx;
        this.module = module;
    }

    public void openDashboard(Player player) {
        boolean admin = player.hasPermission("vicemc.blooddiamond.admin");
        var builder = ctx.gui().builder(GuiKit.title("Blood Diamond"), 6);
        GuiKit.frame(builder);
        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&4How it works",
                "&7Mine shards in the Blood Diamond",
                "&7Mine. Craft them into Blood",
                "&7Diamonds, then redeem them for",
                "&7non-withdrawable store credit.",
                "&4You lose everything if you die inside."), GuiKit.NONE);
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.DIAMOND)
                .name("&4Blood Diamond Mine")
                .lore("&7Shards in inventory: &f" + module.shardsInInventory(player),
                        "&7Blood Diamonds: &f" + module.diamondsInInventory(player),
                        "&7Store credit: &6" + GuiKit.fmt(module.manager().credit(player.getUniqueId())),
                        "&7Shards per diamond: &f" + module.manager().shardsPerDiamond())
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.DIAMOND, "&4Craft Blood Diamond",
                "&7Turn shards into Blood",
                "&7Diamonds."), (p, c) -> openCraft(p));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.EMERALD, "&6Redeem for credit",
                "&7Exchange Blood Diamonds for",
                "&7store credit ($1 each)."), (p, c) -> openRedeem(p));
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.GOLD_INGOT, "&6Store credit",
                "&7Your non-withdrawable credit for",
                "&7ranks and limited offers."), GuiKit.NONE);
        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.BLAZE_ROD, "&4Blood Diamond Wand",
                "&7Right-click to place a Blood",
                "&7Diamond ore. It regenerates",
                "&7after mining.",
                "&4Mining: &f10% shard / 90% red gem"), (p, c) -> module.giveWand(p));
        if (admin) {
            builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.REDSTONE, "&4RMT flags",
                    "&7Review players flagged for",
                    "&7real-money trading."), (p, c) -> openFlags(p, 1));
        }
        builder.open(player);
    }

    // --- Craft ------------------------------------------------------------

    private void openCraft(Player player) {
        Draft draft = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        int shardsPer = module.manager().shardsPerDiamond();
        int needed = shardsPer * draft.count;
        int have = module.shardsInInventory(player);
        var builder = ctx.gui().builder(GuiKit.title("Craft Blood Diamond"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the mine menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.PRISMARINE_SHARD)
                .name("&4Crafting Blood Diamonds")
                .lore("&7Shards per diamond: &f" + shardsPer,
                        "&7You have: &f" + have,
                        "&7Need for " + draft.count + ": &f" + needed,
                        have >= needed ? "&aEnough shards." : "&cNot enough shards.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_2, ItemBuilder.of(Material.GRAY_DYE)
                .name("&7Count: &f" + draft.count)
                .lore("&7Left: &f+1", "&7Right: &f-1",
                        "&7Shift-left: &f+10", "&7Shift-right: &f-10")
                .build(), (p, c) -> {
            int step = c.isShiftClick() ? 10 : 1;
            int delta = (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) ? -step : step;
            draft.count = Math.max(1, Math.min(64, draft.count + delta));
            openCraft(p);
        });
        if (have >= needed) {
            builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.DIAMOND, "&aCraft " + draft.count + "x",
                    "&7Consumes " + needed + " shards from",
                    "&7your inventory."), (p, c) -> {
                if (module.craft(p, draft.count)) {
                    drafts.remove(p.getUniqueId());
                    openDashboard(p);
                }
            });
        } else {
            builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.GRAY_DYE, "&7Locked",
                    "&7You need " + needed + " shards.",
                    "&7You have " + have + "."), GuiKit.NONE);
        }
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to the mine menu."), (p, c) -> openDashboard(p));
        builder.open(player);
    }

    // --- Redeem -----------------------------------------------------------

    private void openRedeem(Player player) {
        int count = module.diamondsInInventory(player);
        var builder = ctx.gui().builder(GuiKit.title("Redeem for credit"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the mine menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.DIAMOND)
                .name("&4Redeem Blood Diamonds")
                .lore("&7You hold: &f" + count,
                        "&7Credit value: &6" + GuiKit.fmt(count),
                        "&7Credit is non-withdrawable and",
                        "&7used for ranks and limited offers.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        if (count > 0) {
            builder.item(GuiKit.ACTION_4, GuiKit.cta(Material.EMERALD, "&aRedeem all " + count + "x",
                    "&7Adds &6" + GuiKit.fmt(count) + "&7 store credit."), (p, c) -> {
                module.redeem(p);
                drafts.remove(p.getUniqueId());
                openDashboard(p);
            });
        } else {
            builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.GRAY_DYE, "&7Nothing to redeem",
                    "&7Craft Blood Diamonds from",
                    "&7shards first."), GuiKit.NONE);
        }
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to the mine menu."), (p, c) -> openDashboard(p));
        builder.open(player);
    }

    // --- RMT flags (admin) ------------------------------------------------

    private void openFlags(Player player, int page) {
        Map<String, String> flags = module.manager().rmtFlags();
        List<String> uuids = new ArrayList<>(flags.keySet());
        int pages = GuiKit.Pages.pages(uuids.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("RMT flags"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the mine menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.REDSTONE, "&4RMT flags",
                "&7" + uuids.size() + " flagged player(s).",
                "&7Real-money trading is bannable."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<String> slice = GuiKit.Pages.slice(uuids, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            String uuid = slice.get(i);
            String reason = flags.get(uuid);
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.playerHead(nameOf(UUID.fromString(uuid)),
                    "&c" + nameOf(UUID.fromString(uuid)),
                    "&4Reason: &f" + reason,
                    "&7Flagged for real-money trading."),
                    (p, c) -> ctx.notifications().msg(p, "&7" + nameOf(UUID.fromString(uuid))
                            + " flagged: " + reason));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openFlags(p, next));
        builder.open(player);
    }

    // --- Helpers ----------------------------------------------------------

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

    private static final class Draft {
        int count = 1;
    }
}
