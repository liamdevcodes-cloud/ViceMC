package net.vicemc.api.util;

import net.kyori.adventure.text.Component;
import net.vicemc.api.service.GUIService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared building blocks for the ViceMC "bright modern" menu style: clean
 * light layouts with a colored header and footer bar, an open (empty) body
 * that shows the default inventory background, item-stack buttons with short
 * lore, money labels, player heads and simple pagination. The primary call to
 * action on confirmation screens glows so the player always knows what to
 * click.
 */
public final class GuiKit {

    private GuiKit() {
    }

    public static final Material FILLER = Material.WHITE_STAINED_GLASS_PANE;
    public static final Material FILL = Material.GRAY_STAINED_GLASS_PANE;
    public static final Material HEADER_BAR = Material.LIGHT_BLUE_STAINED_GLASS_PANE;
    public static final Material FOOTER_BAR = Material.LIGHT_BLUE_STAINED_GLASS_PANE;
    public static final Material ACCENT = Material.LIGHT_BLUE_STAINED_GLASS_PANE;
    public static final Material GOOD_ACCENT = Material.LIME_STAINED_GLASS_PANE;
    public static final Material DANGER_ACCENT = Material.RED_STAINED_GLASS_PANE;

    // Standard dashboard slots (6 rows).
    public static final int HOW_TO = 0;
    public static final int STATUS = 4;
    public static final int CLOSE = 8;
    public static final int ACTION_1 = 11;
    public static final int ACTION_2 = 12;
    public static final int ACTION_3 = 13;
    public static final int ACTION_4 = 14;
    public static final int ACTION_5 = 15;
    public static final int GRID_FIRST = 18;
    public static final int GRID_SIZE = 27;
    public static final int PAGE_PREV = 48;
    public static final int PAGE_INDICATOR = 49;
    public static final int PAGE_NEXT = 50;

    public static Component title(String name) {
        return Text.color("&f" + name);
    }

    /** No-op click callback for decorative items. */
    public static final java.util.function.BiConsumer<Player, ClickType> NONE = (p, c) -> {
    };

    public static ItemStack filler() {
        return ItemBuilder.of(FILLER).name(" ").build();
    }

    public static ItemStack bar(Material material) {
        return ItemBuilder.of(material).name(" ").build();
    }

    /**
     * Complete framed panel: every slot filled with a neutral body color, a
     * light banner across the top row (back/status/close sit on it) and a
     * matching banner across the bottom row (pagination sits on it). The body
     * is fully filled so every menu reads as one even, centered panel instead
     * of floating icons on an empty background.
     */
    public static void frame(GUIService.GuiBuilder builder) {
        for (int slot = 0; slot < 54; slot++) {
            builder.item(slot, bar(FILL), NONE);
        }
        for (int slot = 0; slot < 9; slot++) {
            builder.item(slot, bar(HEADER_BAR), NONE);
        }
        for (int slot = 45; slot < 54; slot++) {
            builder.item(slot, bar(FOOTER_BAR), NONE);
        }
    }

    public static void frame(Inventory inv) {
        ItemStack fill = bar(FILL);
        ItemStack header = bar(HEADER_BAR);
        ItemStack footer = bar(FOOTER_BAR);
        for (int slot = 0; slot < inv.getSize(); slot++) {
            inv.setItem(slot, fill);
        }
        for (int slot = 0; slot < 9; slot++) {
            inv.setItem(slot, header);
        }
        for (int slot = inv.getSize() - 9; slot < inv.getSize(); slot++) {
            inv.setItem(slot, footer);
        }
    }

    public static ItemStack accentPane(String... lore) {
        return ItemBuilder.of(ACCENT).name(" ").lore(lore).build();
    }

    public static ItemStack icon(Material material, String name, String... lore) {
        return ItemBuilder.of(material).name(name).lore(lore).build();
    }

    /** The primary call to action on a screen: bright, glowing, impossible to miss. */
    public static ItemStack cta(Material material, String name, String... lore) {
        return ItemBuilder.of(material).name(name).lore(lore).glow().build();
    }

    public static ItemStack close() {
        return icon(Material.BARRIER, "&cClose", "&7Click to close the menu.");
    }

    public static ItemStack back(String to) {
        return icon(Material.ARROW, "&bBack", "&7Return to " + to + ".");
    }

    public static ItemStack nextPage(int page) {
        return icon(Material.ARROW, "&bNext page", "&7Go to page " + (page + 1) + ".");
    }

    public static ItemStack prevPage(int page) {
        return icon(Material.ARROW, "&bPrevious page", "&7Go to page " + Math.max(1, page - 1) + ".");
    }

    public static ItemStack pageIndicator(int page, int pages) {
        return icon(Material.PAPER, "&fPage &b" + page + " &fof &b" + Math.max(1, pages));
    }

    public static ItemStack pageGap() {
        return bar(FILLER);
    }

    public static String fmt(double value) {
        return Text.moneyPlain(value);
    }

    public static ItemStack money(double value) {
        return icon(Material.GOLD_INGOT, "&a" + fmt(value), "&7Your balance");
    }

    public static ItemStack playerHead(String playerName, String name, String... lore) {
        ItemStack item = ItemBuilder.of(Material.PLAYER_HEAD).name(name).lore(lore).build();
        if (playerName != null && !playerName.isEmpty()) {
            item.editMeta(meta -> {
                if (meta instanceof SkullMeta skull) {
                    var offline = Bukkit.getOfflinePlayerIfCached(playerName);
                    if (offline != null) {
                        skull.setOwningPlayer(offline);
                    }
                }
            });
        }
        return item;
    }

    /** Small helper for paginated content grids. */
    public static final class Pages {
        private Pages() {
        }

        public static int pages(int size, int perPage) {
            return Math.max(1, (int) Math.ceil(size / (double) perPage));
        }

        public static <T> List<T> slice(List<T> items, int page, int perPage) {
            int from = Math.max(0, (page - 1) * perPage);
            if (from >= items.size()) {
                return new ArrayList<>();
            }
            return new ArrayList<>(items.subList(from, Math.min(items.size(), from + perPage)));
        }
    }
}
