package net.vicemc.modules.regions;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom polygon regions. Admins select a multi-vertex polygon footprint with
 * the region wand, save it as a region and then tune its flags (pvp, loot
 * loss, item drops, weapons, damage, flight, hunger, mob spawns, building,
 * teleports, entry/exit, chat and commands). Players get a safe/danger/neutral
 * chat indicator when they enter a zone and can check their zone with
 * /region here.
 */
public final class RegionsModule implements ViceModule {

    private ViceModuleContext ctx;
    private YamlConfig config;
    private RegionsManager manager;
    private RegionsWandListener wand;
    private RegionsListener listener;
    private RegionsGui gui;

    @Override
    public String id() {
        return "regions";
    }

    @Override
    public String displayName() {
        return "Vice Regions";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("regions.yml");
        this.manager = new RegionsManager(ctx);
        this.wand = new RegionsWandListener(ctx, manager, config);
        this.listener = new RegionsListener(ctx, manager, config, ctx.plugin());
        this.gui = new RegionsGui(ctx, manager, wand);

        Bukkit.getPluginManager().registerEvents(wand, ctx.plugin());
        Bukkit.getPluginManager().registerEvents(listener, ctx.plugin());
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(ctx.plugin(), t -> listener.startZoneMonitor(player), null);
        }

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("region")
                .aliases("rg")
                .description("Custom polygon regions")
                .executes(this::region)
                .tabulates((c, a) -> {
                    if (a.size() == 1) {
                        return List.of("wand", "create", "delete", "rename", "flag",
                                "list", "info", "here", "clear", "flags", "help");
                    }
                    String sub = a.get(0).toLowerCase();
                    if ((sub.equals("delete") || sub.equals("rename")
                            || sub.equals("flag") || sub.equals("info")) && a.size() == 2) {
                        return manager.all().stream().map(RegionPolygon::id).toList();
                    }
                    if (sub.equals("flag") && a.size() == 3) {
                        return RegionsFlag.keys();
                    }
                    if (sub.equals("flag") && a.size() == 4) {
                        return List.of("on", "off", "toggle");
                    }
                    return List.of();
                })
                .build());

        ctx.logger().info("Regions module ready.");
    }

    public RegionsManager manager() {
        return manager;
    }

    private void region(CommandContext c) {
        switch (c.arg(0)) {
            case "wand" -> wand(c);
            case "create" -> create(c);
            case "delete" -> delete(c);
            case "rename" -> rename(c);
            case "flag" -> flag(c);
            case "list" -> list(c);
            case "info" -> info(c);
            case "here" -> here(c);
            case "clear" -> clear(c);
            case "flags" -> flags(c);
            case "help" -> c.msg("&6/region wand | create <id> [name] | delete <id> | rename <id> <name> "
                    + "| flag <id> <flag> [on|off] | list | info [id] | here | clear | flags");
            default -> {
                if (c.isPlayer()) {
                    gui.openDashboard(c.player());
                } else {
                    c.msg("&6/region list | here | info <id>");
                }
            }
        }
    }

    private void wand(CommandContext c) {
        if (!c.isPlayer() || !admin(c)) {
            return;
        }
        wand.giveWand(c.player());
    }

    private void create(CommandContext c) {
        if (!c.isPlayer() || !admin(c)) {
            return;
        }
        if (c.size() < 2) {
            c.usage("/region create <id> [name...]");
            return;
        }
        Player player = c.player();
        List<Location> selection = manager.selection(player.getUniqueId());
        if (selection.size() < 3) {
            c.error("No closed selection - use the region wand to select at least 3 vertices first.");
            return;
        }
        World world = selection.get(0).getWorld();
        if (world == null) {
            c.error("Invalid world.");
            return;
        }
        List<int[]> vertices = new ArrayList<>();
        for (Location loc : selection) {
            vertices.add(new int[]{loc.getBlockX(), loc.getBlockZ()});
        }
        String name = c.size() > 2
                ? String.join(" ", java.util.Arrays.copyOfRange(c.args(), 2, c.size()))
                : c.arg(1);
        RegionsManager.CreateResult result = manager.create(c.arg(1), name, world.getName(),
                vertices, world.getMinHeight(), world.getMaxHeight());
        if (result.error() != null) {
            c.error(result.error());
            return;
        }
        manager.clearSelection(player.getUniqueId());
        RegionPolygon region = result.region();
        c.msg("&aCreated region &f" + region.name() + "&a with &f" + region.vertices().size()
                + "&a vertices (&f" + formatArea(region) + "&a). Status: "
                + manager.safety(region).label() + "&a.");
        c.msg("&7Tune it with &e/region flag " + region.id() + " <flag> [on|off]&7.");
    }

    private void delete(CommandContext c) {
        if (!c.isPlayer() || !admin(c)) {
            return;
        }
        if (c.size() < 2) {
            c.usage("/region delete <id>");
            return;
        }
        if (manager.delete(c.arg(1))) {
            c.msg("&cDeleted region &f" + c.arg(1) + "&c.");
        } else {
            c.error("No region with id '" + c.arg(1) + "'.");
        }
    }

    private void rename(CommandContext c) {
        if (!c.isPlayer() || !admin(c)) {
            return;
        }
        if (c.size() < 3) {
            c.usage("/region rename <id> <name...>");
            return;
        }
        RegionPolygon region = manager.byId(c.arg(1));
        if (region == null) {
            c.error("No region with id '" + c.arg(1) + "'.");
            return;
        }
        String name = String.join(" ", java.util.Arrays.copyOfRange(c.args(), 2, c.size()));
        manager.rename(region, name);
        c.msg("&aRenamed region to &f" + name + "&a.");
    }

    private void flag(CommandContext c) {
        if (!c.isPlayer() || !admin(c)) {
            return;
        }
        if (c.size() < 3) {
            c.usage("/region flag <id> <flag> [on|off|toggle]");
            return;
        }
        RegionPolygon region = manager.byId(c.arg(1));
        if (region == null) {
            c.error("No region with id '" + c.arg(1) + "'.");
            return;
        }
        RegionsFlag flag = RegionsFlag.from(c.arg(2));
        if (flag == null) {
            c.error("Unknown flag '" + c.arg(2) + "'. Use /region flags for the full list.");
            return;
        }
        boolean on;
        String setting = c.arg(3, "toggle");
        if (setting.equalsIgnoreCase("on")) {
            on = true;
        } else if (setting.equalsIgnoreCase("off")) {
            on = false;
        } else {
            on = !region.flag(flag);
        }
        manager.setFlag(region, flag, on);
        c.msg("&7Region &f" + region.name() + "&7: &f" + flag.key() + "&7 is now "
                + (on ? "&aON" : "&cOFF") + "&7. Status: " + manager.safety(region).label() + "&7.");
    }

    private void list(CommandContext c) {
        List<RegionPolygon> all = manager.all();
        if (all.isEmpty()) {
            c.msg("&7No regions created yet.");
            return;
        }
        c.msg("&bRegions (" + all.size() + "):");
        for (RegionPolygon region : all) {
            c.msg("&7  &f" + region.name() + " &7(&e" + region.id() + "&7) "
                    + manager.safety(region).label()
                    + " &7- " + region.vertices().size() + " vertices, " + region.world());
        }
    }

    private void info(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        RegionPolygon region = c.size() >= 2 ? manager.byId(c.arg(1)) : manager.at(c.player().getLocation());
        if (region == null) {
            c.error("No region found.");
            return;
        }
        c.msg("&8» &f" + region.name() + " &7(" + region.id() + ") &7- "
                + manager.safety(region).label());
        c.msg("&7  World: &f" + region.world() + "&7, vertices: &f" + region.vertices().size()
                + "&7, footprint: &f" + formatArea(region)
                + "&7, height: &f" + region.minY() + "-" + region.maxY());
        for (RegionsFlag flag : RegionsFlag.values()) {
            c.msg("&7  &f" + flag.key() + ": " + (region.flag(flag) ? "&aON" : "&cOFF"));
        }
    }

    private void here(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        Player player = c.player();
        RegionPolygon region = manager.at(player.getLocation());
        if (region == null) {
            c.msg("&8[&bRegion&8] &7You are in &fThe Wilderness &7- " + Safety.NEUTRAL.label());
            return;
        }
        c.msg("&8[&bRegion&8] &7You are in &f" + region.name() + " &7- "
                + manager.safety(region).label());
        c.msg("&7  pvp: " + onOff(region.flag(RegionsFlag.PVP))
                + " &7loot-drop: " + onOff(region.flag(RegionsFlag.LOOT_DROP))
                + " &7item-drop: " + onOff(region.flag(RegionsFlag.ITEM_DROP))
                + " &7weapons: " + onOff(region.flag(RegionsFlag.WEAPONS)));
    }

    private void clear(CommandContext c) {
        if (!c.isPlayer() || !admin(c)) {
            return;
        }
        manager.clearSelection(c.player().getUniqueId());
        c.msg("&7Region selection cleared.");
    }

    private void flags(CommandContext c) {
        c.msg("&bAvailable region flags:");
        for (RegionsFlag flag : RegionsFlag.values()) {
            c.msg("&7  &f" + flag.key() + (flag.defaultValue() ? " &7(default &aON&7)"
                    : " &7(default &cOFF&7)") + " &7- " + flag.description());
        }
    }

    // --- Helpers ----------------------------------------------------------

    private boolean admin(CommandContext c) {
        if (c.player().hasPermission("vicemc.regions.admin")) {
            return true;
        }
        c.error("You are not authorized.");
        return false;
    }

    private String formatArea(RegionPolygon region) {
        return Math.round(region.footprintArea()) + " blocks²";
    }

    private String onOff(boolean value) {
        return value ? "&aON" : "&cOFF";
    }
}
