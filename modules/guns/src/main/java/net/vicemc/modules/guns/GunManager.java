package net.vicemc.modules.guns;

import com.google.gson.Gson;
import net.kyori.adventure.text.Component;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The gun registry. Gun definitions live in module storage (survive
 * restarts); individual guns are plain items whose state is carried in
 * persistent tags. This class also builds gun and ammo items and reads/writes
 * the per-item state.
 */
public final class GunManager {

    public static final NamespacedKey GUN_KEY = NamespacedKey.fromString("vicemc:gun");
    public static final NamespacedKey GUN_ID_KEY = NamespacedKey.fromString("vicemc:gun-id");
    public static final NamespacedKey GUN_SERIAL_KEY = NamespacedKey.fromString("vicemc:gun-serial");
    public static final NamespacedKey GUN_DUR_KEY = NamespacedKey.fromString("vicemc:gun-durability");
    public static final NamespacedKey GUN_AMMO_KEY = NamespacedKey.fromString("vicemc:gun-ammo");
    public static final NamespacedKey AMMO_KEY = NamespacedKey.fromString("vicemc:ammo");

    private static final String STORE_PREFIX = "def:";
    private static final String SERIAL_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ViceModuleContext ctx;
    private final YamlConfig config;
    private final Gson gson = new Gson();
    private final Map<String, GunDefinition> defs = new ConcurrentHashMap<>();

    public GunManager(ViceModuleContext ctx, YamlConfig config) {
        this.ctx = ctx;
        this.config = config;
        load();
    }

    // --- Definition registry ----------------------------------------------

    private void load() {
        Map<String, String> stored = ctx.storage().moduleDataAll("guns");
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            if (!entry.getKey().startsWith(STORE_PREFIX)) {
                continue;
            }
            try {
                GunDefinition def = gson.fromJson(entry.getValue(), GunDefinition.class);
                if (def != null && !def.id.isEmpty()) {
                    defs.put(def.id, def);
                }
            } catch (RuntimeException ex) {
                ctx.logger().warning("Skipping invalid gun definition '" + entry.getKey() + "': " + ex.getMessage());
            }
        }
        ctx.logger().info("Loaded " + defs.size() + " gun definition(s).");
    }

    public List<GunDefinition> defs() {
        List<GunDefinition> list = new ArrayList<>(defs.values());
        list.sort(Comparator.comparing(d -> d.name.toLowerCase()));
        return list;
    }

    public Optional<GunDefinition> def(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(defs.get(id));
    }

    public boolean exists(String id) {
        return id != null && defs.containsKey(id);
    }

    public void save(GunDefinition def) {
        defs.put(def.id, def);
        ctx.storage().setModuleData("guns", STORE_PREFIX + def.id, gson.toJson(def));
    }

    public void delete(String id) {
        if (id == null) {
            return;
        }
        defs.remove(id);
        ctx.storage().removeModuleData("guns", STORE_PREFIX + id);
    }

    // --- Item detection ---------------------------------------------------

    public boolean isGun(ItemStack item) {
        return ItemBuilder.hasTag(item, GUN_KEY, "true");
    }

    public boolean isAmmo(ItemStack item) {
        return ItemBuilder.hasTag(item, AMMO_KEY, "true");
    }

    public AmmoType ammoOf(ItemStack item) {
        return AmmoType.byId(ItemBuilder.tag(item, AMMO_KEY));
    }

    public GunInstance read(ItemStack item) {
        if (!isGun(item)) {
            return null;
        }
        String id = ItemBuilder.tag(item, GUN_ID_KEY);
        String serial = ItemBuilder.tag(item, GUN_SERIAL_KEY);
        int durability = parseInt(ItemBuilder.tag(item, GUN_DUR_KEY), 0);
        int ammo = parseInt(ItemBuilder.tag(item, GUN_AMMO_KEY), 0);
        GunDefinition def = id == null ? null : defs.get(id);
        return new GunInstance(def, serial == null ? "????" : serial, durability, ammo);
    }

    // --- Item building ----------------------------------------------------

    public ItemStack gunItem(GunInstance instance) {
        GunDefinition def = instance.def();
        if (def == null) {
            return null;
        }
        return gunItem(def, instance.serial(), instance.durability(), instance.ammoInMag());
    }

    public ItemStack gunItem(GunDefinition def, String serial, int durability, int ammo) {
        ItemBuilder builder = ItemBuilder.of(def.bukkitMaterial())
                .name(def.name)
                .lore(lore(def, serial, durability, ammo).toArray(new String[0]))
                .tag(GUN_KEY, "true")
                .tag(GUN_ID_KEY, def.id)
                .tag(GUN_SERIAL_KEY, serial)
                .tag(GUN_DUR_KEY, String.valueOf(durability))
                .tag(GUN_AMMO_KEY, String.valueOf(ammo));
        if (def.modelData > 0) {
            builder.modelData(def.modelData);
        }
        return builder.build();
    }

    /** Rewrites durability + magazine and their lore on a live gun item. */
    public void updateGun(ItemStack item, GunDefinition def, String serial, int durability, int ammo) {
        item.editMeta(meta -> {
            meta.getPersistentDataContainer().set(GUN_DUR_KEY, PersistentDataType.STRING, String.valueOf(durability));
            meta.getPersistentDataContainer().set(GUN_AMMO_KEY, PersistentDataType.STRING, String.valueOf(ammo));
            meta.lore(lore(def, serial, durability, ammo).stream().map(Text::color).toList());
        });
    }

    public ItemStack ammoItem(AmmoType type) {
        Material material = Material.valueOf(config.getString("ammo." + type.id() + ".material", "PAPER"));
        int modelData = config.getInt("ammo." + type.id() + ".model-data", 0);
        ItemBuilder builder = ItemBuilder.of(material)
                .name("&f" + type.display())
                .lore("&7Gun ammunition.", "&8" + type.display())
                .tag(AMMO_KEY, type.id());
        if (modelData > 0) {
            builder.modelData(modelData);
        }
        return builder.build();
    }

    // --- Ammo counting / consuming ---------------------------------------

    public int countAmmo(Player player, AmmoType type) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (type == ammoOf(item)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /** Removes up to {@code amount} matching ammo items. Returns the amount actually removed. */
    public int consumeAmmo(Player player, AmmoType type, int amount) {
        int needed = amount;
        for (ItemStack item : player.getInventory().getContents()) {
            if (needed <= 0) {
                break;
            }
            if (item == null || item.getType().isAir() || type != ammoOf(item)) {
                continue;
            }
            int take = Math.min(item.getAmount(), needed);
            item.setAmount(item.getAmount() - take);
            needed -= take;
        }
        return amount - needed;
    }

    // --- Serials ----------------------------------------------------------

    public String nextSerial() {
        StringBuilder sb = new StringBuilder("VIC-");
        for (int i = 0; i < 6; i++) {
            sb.append(SERIAL_CHARS.charAt(RANDOM.nextInt(SERIAL_CHARS.length())));
        }
        return sb.toString();
    }

    // --- Lore -------------------------------------------------------------

    private List<String> lore(GunDefinition def, String serial, int durability, int ammo) {
        List<String> lines = new ArrayList<>();
        GunType gunType = def.gunType();
        lines.add("&7Serial: &f" + serial);
        lines.add("&7Type: &f" + gunType.display());
        lines.add("&7Ammo: &f" + ammo + "&8/&f" + def.magSize + " &7(" + gunType.ammo().display() + ")");
        lines.add("&7Durability: " + durabilityBar(durability, def.durability) + " &f" + durability + "&8/&f" + def.durability);
        lines.add("&8Right-click: shoot");
        lines.add("&8Shift + right-click: aim");
        lines.add("&8Q: reload");
        if (durability <= 0) {
            lines.add("&c&lBROKEN - cannot fire");
        }
        return lines;
    }

    private String durabilityBar(int durability, int max) {
        int segments = 10;
        double fraction = max <= 0 ? 0 : Math.max(0, Math.min(1, durability / (double) max));
        int filled = (int) Math.round(fraction * segments);
        String color = fraction > 0.5 ? "&a" : (fraction > 0.2 ? "&e" : "&c");
        return color + "|".repeat(filled) + "&7" + "|".repeat(Math.max(0, segments - filled));
    }

    private int parseInt(String value, int def) {
        try {
            return value == null ? def : Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
