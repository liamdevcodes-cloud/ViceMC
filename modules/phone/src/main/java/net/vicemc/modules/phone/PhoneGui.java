package net.vicemc.modules.phone;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.model.Transaction;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The phone menus: a small dropper-style main screen with directions, bank
 * and far chat, plus the bank account, contacts and directions screens.
 */
public final class PhoneGui {

    private static final int PER_PAGE = 27;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final PhoneModule module;
    private final ViceModuleContext ctx;

    public PhoneGui(PhoneModule module) {
        this.module = module;
        this.ctx = module.context();
    }

    // --- Main phone screen (dropper-style 3x3) ----------------------------

    public void openPhone(Player player) {
        String direction = module.directionFor(player.getUniqueId());
        var builder = ctx.gui().builder(GuiKit.title("&fPhone"), 3);
        for (int slot = 0; slot < 27; slot++) {
            builder.item(slot, GuiKit.bar(GuiKit.FILL), GuiKit.NONE);
        }
        builder.item(3, accent(), GuiKit.NONE);
        builder.item(4, GuiKit.icon(Material.COMPASS, "&6Directions",
                "&7Directions to any plot.",
                "&7Target: &f" + (direction == null ? "&7none" : "&e" + direction)),
                (p, c) -> openDirections(p));
        builder.item(5, accent(), GuiKit.NONE);
        builder.item(12, GuiKit.icon(Material.GOLD_INGOT, "&aBank account",
                "&7Check your balance and",
                "&7recent transactions."),
                (p, c) -> openBank(p, 1));
        builder.item(13, ItemBuilder.of(Material.CLOCK)
                .name("&b" + player.getName() + "&b's phone")
                .lore("&7Far-right hotbar slot.",
                        "&7Local chat range: &f" + module.chatRange() + "m",
                        "&7Balance: &f" + GuiKit.fmt(ctx.economy().balance(player.getUniqueId())),
                        "&7Time: &f" + time())
                .build(), GuiKit.NONE);
        builder.item(14, GuiKit.icon(Material.WRITABLE_BOOK, "&eMessages",
                "&7Far chat: message anyone,",
                "&7no matter how far away."),
                (p, c) -> openMessages(p, 1));
        builder.item(21, accent(), GuiKit.NONE);
        builder.item(22, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(23, accent(), GuiKit.NONE);
        builder.open(player);
    }

    // --- Directions -------------------------------------------------------

    public void openDirections(Player player) {
        String target = module.directionFor(player.getUniqueId());
        var builder = ctx.gui().builder(GuiKit.title("Phone - Directions"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the phone"), (p, c) -> openPhone(p));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.COMPASS)
                .name("&6Directions")
                .lore("&7Current target: &f" + (target == null ? "&7none" : target),
                        "&7Your compass points at the plot;",
                        "&7distance and direction show",
                        "&7above your hotbar.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.cta(Material.PAPER, "&aEnter a plot serial",
                "&7Type a serial, e.g. &f001AA&7.",
                "&7Unowned plots work too."), (p, c) -> askDirection(p));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.BARRIER, "&7Clear direction",
                "&7Stop pointing at the current plot."), (p, c) -> {
            module.clearDirection(p.getUniqueId());
            ctx.notifications().msg(p, "&7Phone directions cleared.");
            openDirections(p);
        });
        builder.open(player);
    }

    public void askDirection(Player player) {
        module.prompts().prompt(player,
                "&6Phone &7- type the plot serial you want directions to (e.g. &f001AA&7), or &ccancel&7.",
                (p, serial) -> {
                    if (serial == null || serial.isBlank() || serial.equalsIgnoreCase("cancel")) {
                        ctx.notifications().msg(p, "&7Directions cancelled.");
                        openDirections(p);
                        return;
                    }
                    module.setCompass(p, serial);
                    openDirections(p);
                });
    }

    // --- Bank account -----------------------------------------------------

    public void openBank(Player player, int page) {
        UUID uuid = player.getUniqueId();
        List<Transaction> history = new ArrayList<>(ctx.economy().history(uuid));
        int pages = GuiKit.Pages.pages(history.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Phone - Bank"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the phone"), (p, c) -> openPhone(p));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.GOLD_BLOCK)
                .name("&a" + player.getName() + "'s account")
                .lore("&7Balance: &f" + GuiKit.fmt(ctx.economy().balance(uuid)),
                        "&7Transactions: &f" + history.size())
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Transaction> slice = GuiKit.Pages.slice(history, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            builder.item(GuiKit.GRID_FIRST + i, transactionItem(slice.get(i)), GuiKit.NONE);
        }
        addPaging(builder, player, safe, pages, (p, next) -> openBank(p, next));
        builder.open(player);
    }

    private ItemStack transactionItem(Transaction tx) {
        boolean positive = tx.amount() >= 0;
        return ItemBuilder.of(positive ? Material.LIME_DYE : Material.GRAY_DYE)
                .name((positive ? "&a+" : "&c") + GuiKit.fmt(tx.amount()))
                .lore("&7" + tx.category(),
                        "&7Balance after: &f" + GuiKit.fmt(tx.balanceAfter()),
                        "&7" + STAMP.format(Instant.ofEpochMilli(tx.timestamp())))
                .build();
    }

    // --- Messages / far chat ----------------------------------------------

    public void openMessages(Player player, int page) {
        List<Player> contacts = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(player)) {
                contacts.add(online);
            }
        }
        int pages = GuiKit.Pages.pages(contacts.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Phone - Messages"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the phone"), (p, c) -> openPhone(p));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.WRITABLE_BOOK)
                .name("&eFar chat")
                .lore("&7Message anyone online,",
                        "&7no matter how far away.",
                        "&7Contacts: &f" + contacts.size())
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Player> slice = GuiKit.Pages.slice(contacts, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Player target = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i,
                    GuiKit.playerHead(target.getName(), "&f" + target.getName(),
                            "&7Click to send a far chat message."),
                    (p, c) -> askMessage(p, target));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openMessages(p, next));
        builder.open(player);
    }

    public void askMessage(Player player, Player target) {
        module.setMessageTarget(player.getUniqueId(), target.getUniqueId());
        module.prompts().prompt(player,
                "&6Phone &7- type your message to &f" + target.getName() + "&7, or &ccancel&7.",
                (p, text) -> {
                    UUID to = module.messageTarget(p.getUniqueId());
                    module.setMessageTarget(p.getUniqueId(), null);
                    if (text == null || text.isBlank() || text.equalsIgnoreCase("cancel")) {
                        ctx.notifications().msg(p, "&7Message cancelled.");
                        openPhone(p);
                        return;
                    }
                    if (to == null) {
                        ctx.notifications().warn(p, "&cNo recipient selected.");
                        openPhone(p);
                        return;
                    }
                    module.sendFarChat(p, to, text);
                    openPhone(p);
                });
    }

    // --- Helpers ----------------------------------------------------------

    private ItemStack accent() {
        return GuiKit.accentPane();
    }

    private String time() {
        return new SimpleDateFormat("HH:mm").format(new Date());
    }

    private void addPaging(GUIService.GuiBuilder builder, Player player, int page, int pages,
                           BiConsumer<Player, Integer> opener) {
        if (page > 1) {
            builder.item(GuiKit.PAGE_PREV, GuiKit.prevPage(page), (p, c) -> opener.accept(p, page - 1));
        } else {
            builder.item(GuiKit.PAGE_PREV, GuiKit.pageGap(), GuiKit.NONE);
        }
        builder.item(GuiKit.PAGE_INDICATOR, GuiKit.pageIndicator(page, pages), GuiKit.NONE);
        if (page < pages) {
            builder.item(GuiKit.PAGE_NEXT, GuiKit.nextPage(page), (p, c) -> opener.accept(p, page + 1));
        } else {
            builder.item(GuiKit.PAGE_NEXT, GuiKit.pageGap(), GuiKit.NONE);
        }
    }
}
