package net.vicemc.modules.phone;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.YamlConfig;
import net.vicemc.core.ViceCore;
import net.vicemc.modules.properties.PropertiesModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Vice Phone - a phone that lives permanently in the far-right hotbar slot.
 * Right-click opens a small dropper-style menu with bank account checks,
 * directions to any plot serial (compass plus a live distance/direction read
 * out above the hotbar) and far chat messaging. Normal in-game chat is
 * proximity based: you only see players who are close enough to hear you.
 */
public final class PhoneModule implements ViceModule {

    public static final NamespacedKey PHONE_KEY = NamespacedKey.fromString("vicemc:phone");

    private ViceModuleContext ctx;
    private YamlConfig config;
    private PhoneGui gui;
    private PhonePrompts prompts;
    private PropertiesModule properties;
    private ScheduledTask tickTask;
    private ItemStack phoneTemplate;
    private final Map<UUID, String> directions = new HashMap<>();
    private final Map<UUID, String> lastDirectionLine = new HashMap<>();
    private final Map<UUID, UUID> messageTargets = new HashMap<>();

    @Override
    public String id() {
        return "phone";
    }

    @Override
    public String displayName() {
        return "Vice Phone";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("phone.yml");
        this.prompts = new PhonePrompts(this);
        this.gui = new PhoneGui(this);
        this.properties = (PropertiesModule) ViceCore.get().getModuleRegistry().module("properties").orElse(null);
        if (properties == null) {
            ctx.logger().warning("Properties module not found; plot directions will be unavailable.");
        }

        Bukkit.getPluginManager().registerEvents(prompts, ctx.plugin());
        Bukkit.getPluginManager().registerEvents(new PhoneListener(this), ctx.plugin());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("phone")
                .aliases("cell")
                .description("Open your phone")
                .executes(c -> {
                    if (c.isPlayer()) {
                        gui.openPhone(c.player());
                    } else {
                        c.msg("&6/phone opens the phone in-game.");
                    }
                })
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("setphonetexture")
                .permission("vicemc.phone.admin")
                .description("Set the server-wide phone texture from the item you are holding")
                .executes(c -> {
                    if (c.isPlayer()) {
                        setPhoneTexture(c.player());
                    } else {
                        c.msg("&6/setphonetexture is used in-game.");
                    }
                })
                .build());

        long ticks = Math.max(10L, config.getInt("sweep-seconds", 2) * 20L);
        tickTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(),
                task -> tick(), ticks, ticks);
        ctx.logger().info("Phone module ready.");
    }

    @Override
    public void onDisable() {
        if (tickTask != null) {
            cancelQuietly(tickTask);
            tickTask = null;
        }
    }

    // --- Facade -----------------------------------------------------------

    public ViceModuleContext context() {
        return ctx;
    }

    public YamlConfig config() {
        return config;
    }

    public PhoneGui gui() {
        return gui;
    }

    public PhonePrompts prompts() {
        return prompts;
    }

    public PropertiesModule properties() {
        return properties;
    }

    public boolean isPhone(ItemStack item) {
        return item != null && ItemBuilder.hasTag(item, PHONE_KEY, "true");
    }

    public ItemStack phoneItem() {
        ItemStack template = phoneTemplate;
        if (template == null) {
            template = buildPhoneItem();
            phoneTemplate = template;
        }
        return template.clone();
    }

    private ItemStack buildPhoneItem() {
        String encoded = config.getString("phone.item", "");
        if (!encoded.isEmpty()) {
            try {
                ItemStack base = ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
                return ItemBuilder.of(base)
                        .name("&6Phone")
                        .lore("&7Right-click to open.",
                                "&7Your phone always sits in the",
                                "&7far-right hotbar slot.",
                                "&8Directions, bank and far chat.")
                        .tag(PHONE_KEY, "true")
                        .build();
            } catch (RuntimeException ex) {
                ctx.logger().warning("Stored phone texture could not be loaded; using defaults. " + ex.getMessage());
            }
        }
        String materialName = config.getString("phone.material", "DROPPER");
        int modelData = config.getInt("phone.model-data", 0);
        Material material;
        try {
            material = Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            material = Material.DROPPER;
        }
        ItemBuilder builder = ItemBuilder.of(material)
                .name("&6Phone")
                .lore("&7Right-click to open.",
                        "&7Your phone always sits in the",
                        "&7far-right hotbar slot.",
                        "&8Directions, bank and far chat.")
                .tag(PHONE_KEY, "true");
        if (modelData > 0) {
            builder.modelData(modelData);
        }
        return builder.build();
    }

    /** Makes the item the player is holding the server-wide phone texture. */
    public void setPhoneTexture(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            ctx.notifications().warn(player, "&cHold the custom item you want as the phone texture first.");
            return;
        }
        ItemStack texture = held.clone();
        texture.setAmount(1);
        config.set("phone.item", Base64.getEncoder().encodeToString(texture.serializeAsBytes()));
        config.set("phone.material", texture.getType().name());
        var heldMeta = texture.getItemMeta();
        config.set("phone.model-data", heldMeta != null && heldMeta.hasCustomModelData() ? heldMeta.getCustomModelData() : 0);
        config.save();
        phoneTemplate = null;
        refreshPhones();
        ctx.notifications().msg(player, "&aPhone texture set to &f" + texture.getType().name()
                + (heldMeta != null && heldMeta.hasCustomModelData() ? " &8(#" + heldMeta.getCustomModelData() + ")" : "")
                + "&a for the whole server.");
    }

    /** Replaces every online player's phone with the current texture. */
    public void refreshPhones() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.getScheduler().run(ctx.plugin(), task -> {
                var inv = online.getInventory();
                if (isPhone(inv.getItem(8))) {
                    inv.setItem(8, phoneItem());
                }
            }, null);
        }
    }

    public int chatRange() {
        return Math.max(1, config.getInt("chat-range", 30));
    }

    // --- Directions -------------------------------------------------------

    public String directionFor(UUID uuid) {
        return directions.get(uuid);
    }

    public void setDirection(UUID uuid, String serial) {
        if (serial == null || serial.isBlank()) {
            directions.remove(uuid);
            lastDirectionLine.remove(uuid);
        } else {
            directions.put(uuid, serial.toUpperCase());
        }
    }

    public void clearDirection(UUID uuid) {
        directions.remove(uuid);
        lastDirectionLine.remove(uuid);
    }

    // --- Far chat ---------------------------------------------------------

    public UUID messageTarget(UUID uuid) {
        return messageTargets.get(uuid);
    }

    public void setMessageTarget(UUID uuid, UUID target) {
        if (target == null) {
            messageTargets.remove(uuid);
        } else {
            messageTargets.put(uuid, target);
        }
    }

    public void sendFarChat(Player sender, UUID targetUuid, String text) {
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null) {
            ctx.notifications().warn(sender, "&cThat player is no longer online.");
            return;
        }
        ctx.notifications().msg(target, "&8[&bPhone&8] &f" + sender.getName() + "&7: &f" + text);
        ctx.notifications().msg(sender, "&8[&bPhone&8] &7To &f" + target.getName() + "&7: &f" + text);
    }

    // --- Tick -------------------------------------------------------------

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(ctx.plugin(), task -> {
                ensurePhone(player);
                updateDirection(player);
            }, null);
        }
    }

    /** Puts the phone back into hotbar slot 8, preserving anything in the way.
     *  Also re-syncs a phone that carries the current texture version. */
    public void ensurePhone(Player player) {
        var inv = player.getInventory();
        ItemStack current = inv.getItem(8);
        if (isPhone(current)) {
            if (!current.isSimilar(phoneItem())) {
                inv.setItem(8, phoneItem());
            }
            return;
        }
        if (current != null && !current.getType().isAir()) {
            var leftover = inv.addItem(current);
            if (!leftover.isEmpty()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
            }
        }
        inv.setItem(8, phoneItem());
    }

    private void updateDirection(Player player) {
        String serial = directions.get(player.getUniqueId());
        if (serial == null || properties == null) {
            return;
        }
        var plot = properties.manager().bySerial(serial);
        if (plot == null) {
            clearDirection(player.getUniqueId());
            return;
        }
        if (plot.bukkitWorld() == null) {
            return;
        }
        var loc = player.getLocation();
        if (!loc.getWorld().getName().equalsIgnoreCase(plot.world)) {
            notifyDirection(player, "&6Plot &f" + serial + "&6 is in another world.");
            return;
        }
        double targetX = (plot.minX + plot.maxX) / 2.0 + 0.5;
        double targetZ = (plot.minZ + plot.maxZ) / 2.0 + 0.5;
        double dx = targetX - loc.getX();
        double dz = targetZ - loc.getZ();
        double dist = Math.hypot(dx, dz);
        notifyDirection(player, "&6Plot &f" + serial + "&6: &f" + meters(dist) + "m &7" + compass(dx, dz));
    }

    private void notifyDirection(Player player, String line) {
        if (line.equals(lastDirectionLine.get(player.getUniqueId()))) {
            return;
        }
        lastDirectionLine.put(player.getUniqueId(), line);
        ctx.notifications().action(player, line);
    }

    private int meters(double dist) {
        return (int) Math.round(dist / 5.0) * 5;
    }

    public void setCompass(Player player, String serial) {
        if (properties == null) {
            ctx.notifications().warn(player, "&cPlot directions are not available.");
            return;
        }
        var plot = properties.manager().bySerial(serial);
        if (plot == null) {
            ctx.notifications().warn(player, "&cPlot &f" + serial + "&c not found.");
            return;
        }
        if (plot.bukkitWorld() == null) {
            ctx.notifications().warn(player, "&cThat plot's world is not loaded.");
            return;
        }
        double x = (plot.minX + plot.maxX) / 2.0 + 0.5;
        double z = (plot.minZ + plot.maxZ) / 2.0 + 0.5;
        player.setCompassTarget(new Location(plot.bukkitWorld(), x, plot.minY, z));
        setDirection(player.getUniqueId(), serial);
        ctx.notifications().msg(player, "&aPhone directions set to &f" + serial
                + "&a. Follow the compass or the distance above your hotbar.");
    }

    /** Compass label for a horizontal offset (dx, dz). E=+X, S=+Z. */
    static String compass(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        String[] dirs = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
        int index = Math.floorMod((int) Math.round(angle / 45.0), 8);
        return dirs[index];
    }

    private static void cancelQuietly(ScheduledTask task) {
        try {
            task.cancel();
        } catch (IllegalStateException ignored) {
        }
    }
}
