package net.vicemc.modules.economy;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.model.Transaction;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wallet menu: balance, recent transactions, full history and a pay wizard -
 * all in the dark modern menu style.
 */
public final class WalletGui {

    private static final int PER_PAGE = 27;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ViceModuleContext ctx;
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();

    public WalletGui(ViceModuleContext ctx) {
        this.ctx = ctx;
    }

    public void openDashboard(Player player) {
        UUID me = player.getUniqueId();
        List<Transaction> recent = ctx.economy().history(me);
        var builder = ctx.gui().builder(GuiKit.title("Wallet"), 6);
        GuiKit.frame(builder);
        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&6How it works",
                "&7Your wallet. Send money to",
                "&7other players and review every",
                "&7transaction here."), GuiKit.NONE);
        builder.item(GuiKit.STATUS, GuiKit.money(ctx.economy().balance(me)), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.GOLD_INGOT, "&6Send money",
                "&7Pay another player a",
                "&7custom amount."), (p, c) -> openPayPlayer(p, 1));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.WRITABLE_BOOK, "&7History",
                "&7Browse all of your",
                "&7transactions."), (p, c) -> openHistory(p, 1));
        List<Transaction> slice = recent.size() > PER_PAGE
                ? new ArrayList<>(recent.subList(0, PER_PAGE)) : recent;
        for (int i = 0; i < slice.size(); i++) {
            builder.item(GuiKit.GRID_FIRST + i, txItem(slice.get(i)),
                    (p, c) -> openHistory(p, 1));
        }
        builder.open(player);
    }

    // --- History ----------------------------------------------------------

    private void openHistory(Player player, int page) {
        UUID me = player.getUniqueId();
        List<Transaction> history = new ArrayList<>(ctx.economy().history(me));
        int pages = GuiKit.Pages.pages(history.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Transaction history"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the wallet"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.WRITABLE_BOOK, "&7History",
                "&7" + history.size() + " transaction(s).",
                "&7Balance: &a" + GuiKit.fmt(ctx.economy().balance(me))), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Transaction> slice = GuiKit.Pages.slice(history, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            builder.item(GuiKit.GRID_FIRST + i, txItem(slice.get(i)), GuiKit.NONE);
        }
        addPaging(builder, player, safe, pages, (p, next) -> openHistory(p, next));
        builder.open(player);
    }

    // --- Pay wizard -------------------------------------------------------

    private void openPayPlayer(Player player, int page) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.removeIf(p -> p.getUniqueId().equals(player.getUniqueId()));
        int pages = GuiKit.Pages.pages(online.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Send money"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the wallet"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.GOLD_INGOT, "&6Send money",
                "&7Pick who to pay.",
                "&7Your balance: &a" + GuiKit.fmt(ctx.economy().balance(player.getUniqueId()))),
                GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Player> slice = GuiKit.Pages.slice(online, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Player target = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.playerHead(target.getName(),
                    "&e" + target.getName(),
                    "&7Balance: &f" + GuiKit.fmt(ctx.economy().balance(target.getUniqueId())),
                    "&aClick to pay."), (p, c) -> openPayAmount(p, target.getUniqueId()));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openPayPlayer(p, next));
        builder.open(player);
    }

    private void openPayAmount(Player player, UUID target) {
        Draft draft = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        var builder = ctx.gui().builder(GuiKit.title("Pay " + nameOf(target)), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the player picker"), (p, c) -> openPayPlayer(p, 1));
        builder.item(GuiKit.STATUS, GuiKit.playerHead(nameOf(target),
                "&ePaying: " + nameOf(target),
                "&7Your balance: &a" + GuiKit.fmt(ctx.economy().balance(player.getUniqueId()))),
                GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, ItemBuilder.of(Material.GRAY_DYE)
                .name("&7Amount: &f" + GuiKit.fmt(draft.amount))
                .lore("&7Left: &f+1,000", "&7Right: &f-1,000",
                        "&7Shift-left: &f+10,000", "&7Shift-right: &f-10,000")
                .build(), (p, c) -> {
            int step = c.isShiftClick() ? 10000 : 1000;
            int delta = (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) ? -step : step;
            draft.amount = Math.max(1000, Math.min(100_000_000, draft.amount + delta));
            openPayAmount(p, target);
        });
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.GOLD_INGOT, "&aSend " + GuiKit.fmt(draft.amount),
                "&7Transfer to " + nameOf(target) + ".",
                "&7Fees and garnishment apply as usual."), (p, c) -> {
            var result = ctx.economy().transfer(p.getUniqueId(), target, draft.amount, "pay");
            if (!result.success()) {
                ctx.notifications().warn(p, "Payment failed: " + result.detail());
                openPayAmount(p, target);
                return;
            }
            Player targetPlayer = Bukkit.getPlayer(target);
            if (targetPlayer != null) {
                ctx.notifications().msg(targetPlayer,
                        "&a" + p.getName() + " paid you &f" + GuiKit.fmt(draft.amount));
            }
            drafts.remove(p.getUniqueId());
            openDashboard(p);
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to the player picker."), (p, c) -> openPayPlayer(p, 1));
        builder.open(player);
    }

    // --- Helpers ----------------------------------------------------------

    private ItemStack txItem(Transaction t) {
        boolean credit = t.amount() >= 0;
        return ItemBuilder.of(credit ? Material.LIME_DYE : Material.RED_DYE)
                .name((credit ? "&a+" : "&c") + GuiKit.fmt(t.amount()))
                .lore("&7" + t.category(),
                        "&7" + TIME.format(Instant.ofEpochMilli(t.timestamp())),
                        "&7Balance after: &f" + GuiKit.fmt(t.balanceAfter()))
                .build();
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

    private static final class Draft {
        double amount = 1000;
    }
}
