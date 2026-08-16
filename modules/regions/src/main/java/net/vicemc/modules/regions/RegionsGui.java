package net.vicemc.modules.regions;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Regions menu: the region list (dashboard) and a per-region flag editor. The
 * flag editor shows every flag as a toggle so a zone can be tuned from a safe
 * town square to a wide-open war zone without touching commands.
 */
public final class RegionsGui {

    private static final int PER_PAGE = 27;

    private final ViceModuleContext ctx;
    private final RegionsManager manager;
    private final RegionsWandListener wand;

    public RegionsGui(ViceModuleContext ctx, RegionsManager manager, RegionsWandListener wand) {
        this.ctx = ctx;
        this.manager = manager;
        this.wand = wand;
    }

    public void openDashboard(Player player) {
        boolean admin = player.hasPermission("vicemc.regions.admin");
        List<RegionPolygon> all = manager.all();
        int pages = GuiKit.Pages.pages(all.size(), PER_PAGE);
        var builder = ctx.gui().builder(GuiKit.title("Regions"), 6);
        GuiKit.frame(builder);
        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&bHow it works",
                "&7Admins select a polygon with",
                "&7the region wand, save it and",
                "&7tune its flags. Players get a",
                "&7safe/danger/neutral indicator",
                "&7when they enter a zone.",
                "&7&o/region here to check your zone."), GuiKit.NONE);
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.MAP)
                .name("&bRegions")
                .lore("&7" + all.size() + " region(s).",
                        "&7Click a region to edit its flags.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        if (admin) {
            builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.GOLDEN_AXE, "&aGet region wand",
                    "&7Adds the wand to your inventory.",
                    "&7Left-click blocks to add vertices,",
                    "&7right-click a block to close."), (p, c) -> wand.giveWand(p));
        } else {
            builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.PAPER, "&7Regions on this server",
                    "&7Admins manage zones here.",
                    "&7Run &e/region here&7 to check",
                    "&7the zone you are standing in."), GuiKit.NONE);
        }
        if (admin) {
            List<RegionPolygon> slice = GuiKit.Pages.slice(all, 1, PER_PAGE);
            for (int i = 0; i < slice.size(); i++) {
                RegionPolygon region = slice.get(i);
                Safety safety = manager.safety(region);
                builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(flagMaterial(safety))
                        .name("&f" + region.name())
                        .lore("&7Id: &e" + region.id(),
                                "&7World: &f" + region.world(),
                                "&7Status: " + safety.label(),
                                "&7Vertices: &f" + region.vertices().size(),
                                "&7Click to edit flags.")
                        .build(), (p, c) -> openRegion(p, region));
            }
        } else {
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.GREEN_DYE, "&aSafe zones",
                    "&7No pvp, no loot loss."), GuiKit.NONE);
            builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.YELLOW_DYE, "&eNeutral zones",
                    "&7Mixed rules."), GuiKit.NONE);
            builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.RED_DYE, "&cDanger zones",
                    "&7Pvp and loot loss allowed."), GuiKit.NONE);
        }
        builder.open(player);
    }

    private void openRegion(Player player, RegionPolygon region) {
        boolean admin = player.hasPermission("vicemc.regions.admin");
        Safety safety = manager.safety(region);
        var builder = ctx.gui().builder(GuiKit.title("Region: " + region.name()), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the region list"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, ItemBuilder.of(flagMaterial(safety))
                .name("&f" + region.name())
                .lore("&7Id: &e" + region.id(),
                        "&7World: &f" + region.world(),
                        "&7Status: " + safety.label(),
                        "&7Vertices: &f" + region.vertices().size(),
                        "&7Footprint: &f" + Math.round(region.footprintArea()) + " blocks²",
                        "&7Height: &f" + region.minY() + " - " + region.maxY())
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        if (!admin) {
            builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Admins only",
                    "&7Only admins can edit region flags."), GuiKit.NONE);
            builder.open(player);
            return;
        }
        RegionsFlag[] flags = RegionsFlag.values();
        for (int i = 0; i < flags.length; i++) {
            RegionsFlag flag = flags[i];
            boolean on = region.flag(flag);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(on ? Material.LIME_DYE : Material.GRAY_DYE)
                    .name((on ? "&a&l" : "&c&l") + flag.key() + (on ? " &7- ON" : " &7- OFF"))
                    .lore("&7" + flag.description(),
                            "&7Click to " + (on ? "disable" : "enable") + ".")
                    .build(), (p, c) -> {
                manager.setFlag(region, flag, !region.flag(flag));
                openRegion(p, region);
            });
        }
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&cDelete region",
                "&7Removes this region permanently."), (p, c) -> {
            manager.delete(region.id());
            ctx.notifications().msg(p, "&cDeleted region &f" + region.name() + "&c.");
            openDashboard(p);
        });
        builder.open(player);
    }

    private Material flagMaterial(Safety safety) {
        return switch (safety) {
            case SAFE -> Material.LIME_DYE;
            case DANGER -> Material.RED_DYE;
            case NEUTRAL -> Material.YELLOW_DYE;
        };
    }
}
