package net.vicemc.modules.guns;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Firearms with unique serial numbers, per-gun durability, magazine-based
 * reloading and an arsenal GUI for admins to create guns from any held item,
 * spawn them and hand out ammo.
 */
public final class GunsModule implements ViceModule {

    private ViceModuleContext ctx;
    private YamlConfig config;
    private GunManager guns;
    private GunsGui gui;
    private GunPrompts prompts;
    private GunsListener listener;

    @Override
    public String id() {
        return "guns";
    }

    @Override
    public String displayName() {
        return "Vice Guns";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("guns.yml");
        this.guns = new GunManager(ctx, config);
        this.gui = new GunsGui(this);
        this.prompts = new GunPrompts(this);
        this.listener = new GunsListener(this);

        Bukkit.getPluginManager().registerEvents(listener, ctx.plugin());
        Bukkit.getPluginManager().registerEvents(prompts, ctx.plugin());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("guns")
                .description("Open the firearms arsenal")
                .executes(c -> {
                    if (c.isPlayer() && c.player().hasPermission("vicemc.guns.admin")) {
                        gui.openAdmin(c.player());
                    } else {
                        c.msg("&6/guns opens the arsenal in-game. You need the vicemc.guns.admin permission.");
                    }
                })
                .build());

        ctx.logger().info("Guns module ready.");
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            listener.shutdown();
        }
    }

    // --- Facade -----------------------------------------------------------

    public ViceModuleContext context() {
        return ctx;
    }

    public YamlConfig config() {
        return config;
    }

    public GunManager guns() {
        return guns;
    }

    public GunsGui gui() {
        return gui;
    }

    public GunPrompts prompts() {
        return prompts;
    }

    /** FOV magnification for a gun type while aiming, config-tunable. */
    public double aimZoom(GunType type) {
        return config.getDouble("aim." + type.name().toLowerCase(), type.aimZoom());
    }

    // --- Admin actions ----------------------------------------------------

    /** Spawns a fresh, fully-loaded gun with a brand new serial to the target. */
    public void giveGun(Player admin, Player target, GunDefinition def) {
        String serial = guns().nextSerial();
        GunInstance instance = new GunInstance(def, serial, def.durability, def.magSize);
        ItemStack item = guns().gunItem(instance);
        var leftover = target.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            target.getWorld().dropItemNaturally(target.getLocation(), leftover.values().iterator().next());
        }
        ctx.notifications().msg(admin, "&aSpawned &f" + def.name + "&a (serial &f" + serial
                + "&a) to &f" + target.getName() + "&a.");
        if (!admin.equals(target)) {
            ctx.notifications().msg(target, "&aYou received &f" + def.name
                    + "&a (serial &f" + serial + "&a).");
        }
    }

    public void giveAmmo(Player admin, AmmoType type, int amount) {
        ItemStack item = guns().ammoItem(type);
        item.setAmount(Math.max(1, amount));
        var leftover = admin.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            admin.getWorld().dropItemNaturally(admin.getLocation(), leftover.values().iterator().next());
        }
        ctx.notifications().msg(admin, "&aHere is &f" + amount + " &fx " + type.display() + "&a.");
    }

    /** Repairs the gun the admin is holding. */
    public boolean repairHeld(Player admin) {
        ItemStack held = admin.getInventory().getItemInMainHand();
        GunInstance instance = guns().read(held);
        if (instance == null || !instance.defOk()) {
            ctx.notifications().warn(admin, "&cHold a gun to repair it.");
            return false;
        }
        if (!instance.broken() && instance.durability() >= instance.def().durability) {
            ctx.notifications().msg(admin, "&7That gun is already in mint condition.");
            return true;
        }
        guns().updateGun(held, instance.def(), instance.serial(),
                instance.def().durability, instance.ammoInMag());
        ctx.notifications().msg(admin, "&aRepaired &f" + instance.def().name
                + "&a (serial &f" + instance.serial() + "&a).");
        return true;
    }

    // --- Sounds -----------------------------------------------------------
    // Sound specs are "KEY|volume|pitch". KEY can be a Bukkit sound enum name
    // (ENTITY_GENERIC_EXPLODE) or a custom namespaced sound from a resource
    // pack (sounds:glock18), which is played via the string overload.

    public void playShot(Location loc, GunType type) {
        playSound(loc, readSound("sounds.shot." + type.name().toLowerCase(),
                "ENTITY_GENERIC_EXPLODE|0.8|1.2"));
    }

    public void playShot(Location loc, GunDefinition def) {
        if (def != null && def.sound != null && !def.sound.isBlank()) {
            playSound(loc, parseSpec(def.sound, ""));
            return;
        }
        playShot(loc, def != null ? def.gunType() : GunType.HANDGUN);
    }

    public void playReload(Player player) {
        playFor(player, readSound("sounds.reload", "BLOCK_PISTON_EXTEND|0.5|1.0"));
    }

    public void playReload(Player player, GunDefinition def) {
        if (def != null && def.reloadSound != null && !def.reloadSound.isBlank()) {
            playFor(player, parseSpec(def.reloadSound, ""));
        }
    }

    public void playReloaded(Player player) {
        playFor(player, readSound("sounds.reloaded", "BLOCK_PISTON_CONTRACT|0.6|0.9"));
    }

    public void playDry(Player player) {
        playFor(player, readSound("sounds.dry", "UI_BUTTON_CLICK|0.4|0.5"));
    }

    public void playAim(Player player) {
        playFor(player, readSound("sounds.aim", "BLOCK_LEVER_CLICK|0.5|1.5"));
    }

    public void playHit(Location loc) {
        playSound(loc, readSound("sounds.hit", "ENTITY_GENERIC_EXPLODE|0.4|1.8"));
    }

    public List<String> customSounds() {
        File pluginFolder = ctx.plugin().getDataFolder().getParentFile();
        File soundsFolder = new File(pluginFolder, "ItemsAdder/contents/sounds/sounds");
        File[] files = soundsFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".ogg"));
        if (files == null) {
            return List.of();
        }
        List<String> sounds = new ArrayList<>();
        for (File file : files) {
            String name = file.getName();
            sounds.add("sounds:" + name.substring(0, name.length() - 4));
        }
        Collections.sort(sounds);
        return sounds;
    }

    private void playSound(Location loc, SoundCfg cfg) {
        if (cfg.custom()) {
            loc.getWorld().playSound(loc, cfg.sound(), cfg.volume(), cfg.pitch());
        } else {
            loc.getWorld().playSound(loc, parseSound(cfg.sound()), cfg.volume(), cfg.pitch());
        }
    }

    private void playFor(Player player, SoundCfg cfg) {
        if (cfg.custom()) {
            player.playSound(player.getLocation(), cfg.sound(), cfg.volume(), cfg.pitch());
        } else {
            player.playSound(player.getLocation(), parseSound(cfg.sound()), cfg.volume(), cfg.pitch());
        }
    }

    private SoundCfg readSound(String path, String def) {
        return parseSpec(config.getString(path, def), def);
    }

    private SoundCfg parseSpec(String raw, String def) {
        String value = (raw == null || raw.isBlank()) ? def : raw;
        String[] parts = value.split("\\|");
        String sound = parts.length > 0 && !parts[0].isBlank() ? parts[0] : "ENTITY_GENERIC_EXPLODE";
        float volume = parts.length > 1 ? parseFloat(parts[1], 1.0f) : 1.0f;
        float pitch = parts.length > 2 ? parseFloat(parts[2], 1.0f) : 1.0f;
        return new SoundCfg(sound, volume, pitch);
    }

    @SuppressWarnings("removal")
    private Sound parseSound(String name) {
        try {
            return name == null ? Sound.ENTITY_GENERIC_EXPLODE : Sound.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return Sound.ENTITY_GENERIC_EXPLODE;
        }
    }

    private float parseFloat(String value, float def) {
        try {
            return value == null ? def : Float.parseFloat(value);
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private record SoundCfg(String sound, float volume, float pitch) {
        boolean custom() {
            return sound != null && sound.indexOf(':') >= 0;
        }
    }
}
