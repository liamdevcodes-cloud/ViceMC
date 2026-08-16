package net.vicemc.core;

import net.vicemc.api.model.Region;
import net.vicemc.api.service.BuildProtectionService;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandService;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.service.EconomyService;
import net.vicemc.api.service.EventBus;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.service.NotificationService;
import net.vicemc.api.service.RegionService;
import net.vicemc.api.service.StorageService;
import net.vicemc.core.listener.BuildProtectionListener;
import net.vicemc.core.service.impl.BuildProtectionServiceImpl;
import net.vicemc.core.service.impl.CommandServiceImpl;
import net.vicemc.core.service.impl.EconomyServiceImpl;
import net.vicemc.core.service.impl.EventBusImpl;
import net.vicemc.core.service.impl.GUIServiceImpl;
import net.vicemc.core.service.impl.NotificationServiceImpl;
import net.vicemc.core.service.impl.RegionServiceImpl;
import net.vicemc.core.service.impl.StorageServiceImpl;
import net.vicemc.core.util.AccountListener;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ViceCore - the roleplay server framework. Provides the module registry and
 * all shared services; feature modules plug into it.
 */
public final class ViceCore extends JavaPlugin {

    private static ViceCore instance;

    private StorageServiceImpl storage;
    private EconomyServiceImpl economy;
    private CommandServiceImpl commands;
    private GUIServiceImpl gui;
    private RegionServiceImpl regions;
    private NotificationServiceImpl notifications;
    private EventBusImpl events;
    private BuildProtectionServiceImpl buildProtection;
    private ModuleRegistry moduleRegistry;
    private final Map<UUID, Location[]> selections = new HashMap<>();

    private static final List<String> REGION_SUBCOMMANDS =
            List.of("pos1", "pos2", "create", "delete", "list", "reload");

    public static ViceCore get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        this.storage = new StorageServiceImpl(this);
        this.economy = new EconomyServiceImpl(storage);
        this.commands = new CommandServiceImpl();
        this.gui = new GUIServiceImpl(this);
        this.regions = new RegionServiceImpl(this);
        this.notifications = new NotificationServiceImpl();
        this.events = new EventBusImpl();
        this.buildProtection = new BuildProtectionServiceImpl(storage);
        this.moduleRegistry = new ModuleRegistry();

        getServer().getPluginManager().registerEvents(new AccountListener(economy), this);
        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(new BuildProtectionListener(buildProtection), this);

        commands.register(this, CommandSpec.builder()
                .name("vregion")
                .aliases("region")
                .permission("vicemc.admin")
                .description("Manage ViceCore regions")
                .executes(this::vregion)
                .tabulates((c, a) -> {
                    if (a.size() <= 1) {
                        return REGION_SUBCOMMANDS;
                    }
                    if ("create".equals(a.get(0)) && a.size() > 2) {
                        return List.of("blooddiamond", "heist:bank", "heist:jewelry", "heist:casino",
                                "heist:island", "gang:north", "gang:south", "government");
                    }
                    if ("delete".equals(a.get(0))) {
                        return regions().all().stream().map(Region::id).toList();
                    }
                    return List.of();
                })
                .build());

        commands.register(this, CommandSpec.builder()
                .name("glow")
                .permission("vicemc.glow")
                .description("Make your held item glow like an enchanted item")
                .executes(this::glow)
                .build());

        commands.register(this, CommandSpec.builder()
                .name("buildermode")
                .aliases("bm", "build")
                .permission("vicemc.buildermode.use")
                .description("Toggle map-building mode on/off")
                .executes(this::buildermode)
                .tabulates((c, a) -> a.size() == 1 ? List.of("on", "off") : List.of())
                .build());

        getLogger().info("ViceCore enabled. Awaiting modules...");
    }

    @Override
    public void onDisable() {
        if (moduleRegistry != null) {
            moduleRegistry.shutdownAll();
        }
        if (storage != null) {
            storage.close();
        }
        instance = null;
    }

    public StorageService storage() {
        return storage;
    }

    public EconomyService economy() {
        return economy;
    }

    public CommandService commands() {
        return commands;
    }

    public GUIService gui() {
        return gui;
    }

    public RegionService regions() {
        return regions;
    }

    public NotificationService notifications() {
        return notifications;
    }

    public EventBus events() {
        return events;
    }

    public BuildProtectionService buildProtection() {
        return buildProtection;
    }

    public ModuleRegistry getModuleRegistry() {
        return moduleRegistry;
    }

    // --- /glow ------------------------------------------------------------

    /**
     * Toggles the enchantment glint on the held item without adding a real
     * enchantment or hiding existing ones (glint override, 1.20.5+).
     */
    private void glow(CommandContext c) {
        if (!c.isPlayer()) {
            c.error("Only players can use this.");
            return;
        }
        Player player = c.player();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            c.error("Hold the item you want to glow in your main hand.");
            return;
        }
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        boolean currentlyGlowing = meta != null && meta.hasEnchantmentGlintOverride()
                && Boolean.TRUE.equals(meta.getEnchantmentGlintOverride());
        item.editMeta(m -> m.setEnchantmentGlintOverride(currentlyGlowing ? null : Boolean.TRUE));
        player.getInventory().setItemInMainHand(item);
        if (currentlyGlowing) {
            c.msg("&7Removed the glow from your &f" + item.getType().name() + "&7.");
        } else {
            c.msg("&aYour &f" + item.getType().name() + "&a now glows like an enchanted item.");
        }
    }

    // --- /buildermode ------------------------------------------------------

    /**
     * Toggles whether the player may build on the otherwise-protected map.
     */
    private void buildermode(CommandContext c) {
        if (!c.isPlayer()) {
            c.error("Only players can use this.");
            return;
        }
        Player player = c.player();
        boolean on;
        if (c.size() == 0) {
            on = !buildProtection.builderMode(player.getUniqueId());
        } else if (c.arg(0).equalsIgnoreCase("on")) {
            on = true;
        } else if (c.arg(0).equalsIgnoreCase("off")) {
            on = false;
        } else {
            c.usage("/buildermode [on|off]");
            return;
        }
        buildProtection.setBuilderMode(player.getUniqueId(), on);
        if (on) {
            c.msg("&aBuilder mode ON - you can build anywhere.");
        } else {
            c.msg("&7Builder mode OFF - the map is protected again.");
        }
    }

    // --- /vregion ---------------------------------------------------------

    private void vregion(CommandContext c) {
        switch (c.arg(0)) {
            case "pos1" -> setSelection(c, 0);
            case "pos2" -> setSelection(c, 1);
            case "create" -> createRegion(c);
            case "delete" -> deleteRegion(c);
            case "list" -> listRegions(c);
            case "reload" -> reloadRegions(c);
            default -> c.msg("&6/vregion pos1 | pos2 | create <id> [tags...] | delete <id> | list | reload");
        }
    }

    private void setSelection(CommandContext c, int corner) {
        if (!c.isPlayer()) {
            c.error("Only players can set positions.");
            return;
        }
        Player player = c.player();
        Location loc = player.getLocation();
        selections.computeIfAbsent(player.getUniqueId(), k -> new Location[2])[corner] = loc;
        c.msg("&aPosition " + (corner + 1) + " set: &f"
                + loc.getWorld().getName() + " " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
    }

    private void createRegion(CommandContext c) {
        if (c.size() < 2) {
            c.usage("/vregion create <id> [tags...]");
            return;
        }
        if (!c.isPlayer()) {
            c.error("Only players can create regions from a selection.");
            return;
        }
        String id = c.arg(1);
        if (regions().byId(id).isPresent()) {
            c.error("A region with id '" + id + "' already exists.");
            return;
        }
        Location[] sel = selections.get(c.player().getUniqueId());
        if (sel == null || sel[0] == null || sel[1] == null) {
            c.error("Set both corners first: /vregion pos1 and /vregion pos2.");
            return;
        }
        if (!sel[0].getWorld().getUID().equals(sel[1].getWorld().getUID())) {
            c.error("Both corners must be in the same world.");
            return;
        }
        Set<String> tags = new LinkedHashSet<>();
        for (int i = 2; i < c.size(); i++) {
            String tag = c.arg(i);
            if (!tag.isBlank()) {
                tags.add(tag);
            }
        }
        Region region = regions().create(id, sel[0].getWorld().getName(),
                sel[0].getBlockX(), sel[0].getBlockY(), sel[0].getBlockZ(),
                sel[1].getBlockX(), sel[1].getBlockY(), sel[1].getBlockZ(), tags);
        selections.remove(c.player().getUniqueId());
        c.msg("&aCreated region &f" + region.id() + "&a: " + region.serialized()
                + (tags.isEmpty() ? " &7(no tags)" : " &7tags: " + String.join(", ", tags)));
    }

    private void deleteRegion(CommandContext c) {
        String id = c.arg(1);
        if (id.isEmpty() || regions().byId(id).isEmpty()) {
            c.error("Unknown region id.");
            return;
        }
        regions().delete(id);
        c.msg("&cDeleted region &f" + id + "&c.");
    }

    private void listRegions(CommandContext c) {
        Collection<Region> all = regions().all();
        if (all.isEmpty()) {
            c.msg("&7No regions defined. Create one with /vregion pos1 + pos2 + create.");
            return;
        }
        c.msg("&6Regions (" + all.size() + "):");
        for (Region region : all) {
            c.msg("  &f" + region.id() + " &7" + region.serialized()
                    + (region.tags().isEmpty() ? "" : "  &b" + String.join(", ", region.tags())));
        }
    }

    private void reloadRegions(CommandContext c) {
        regions.reload();
        c.msg("&aReloaded regions.yml: " + regions().all().size() + " regions loaded.");
    }
}
