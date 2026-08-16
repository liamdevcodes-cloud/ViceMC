package net.vicemc.modules.guns;

import org.bukkit.Material;

/**
 * The broad firearm categories. Each carries the default tuning applied when
 * an admin creates a new gun of that type; every value can be tuned per gun
 * in the arsenal GUI afterwards.
 */
public enum GunType {

    HANDGUN("Handgun", Material.IRON_BLOCK, 6.0, 12, 3.0, 60, 300, 1, 1.5, AmmoType.PISTOL, 3.0, 1.2),
    SMG("SMG", Material.GOLD_BLOCK, 4.0, 30, 10.0, 50, 400, 1, 2.0, AmmoType.SMG, 5.0, 1.2),
    RIFLE("Rifle", Material.DIAMOND_BLOCK, 9.0, 20, 4.5, 100, 500, 1, 2.0, AmmoType.RIFLE, 2.0, 1.7),
    SHOTGUN("Shotgun", Material.REDSTONE_BLOCK, 5.0, 6, 1.2, 40, 250, 6, 3.0, AmmoType.SHOTGUN, 8.0, 1.4),
    SNIPER("Sniper", Material.EMERALD_BLOCK, 20.0, 5, 0.6, 150, 200, 1, 3.5, AmmoType.SNIPER, 0.6, 3.0);

    private final String display;
    private final Material icon;
    private final double damage;
    private final int magSize;
    private final double fireRate;
    private final int range;
    private final int durability;
    private final int pellets;
    private final double reloadSeconds;
    private final AmmoType ammo;
    private final double spread;
    private final double aimZoom;

    GunType(String display, Material icon, double damage, int magSize, double fireRate,
            int range, int durability, int pellets, double reloadSeconds, AmmoType ammo, double spread,
            double aimZoom) {
        this.display = display;
        this.icon = icon;
        this.damage = damage;
        this.magSize = magSize;
        this.fireRate = fireRate;
        this.range = range;
        this.durability = durability;
        this.pellets = pellets;
        this.reloadSeconds = reloadSeconds;
        this.ammo = ammo;
        this.spread = spread;
        this.aimZoom = aimZoom;
    }

    public String display() {
        return display;
    }

    public Material icon() {
        return icon;
    }

    public double damage() {
        return damage;
    }

    public int magSize() {
        return magSize;
    }

    public double fireRate() {
        return fireRate;
    }

    public int range() {
        return range;
    }

    public int durability() {
        return durability;
    }

    public int pellets() {
        return pellets;
    }

    public double reloadSeconds() {
        return reloadSeconds;
    }

    public AmmoType ammo() {
        return ammo;
    }

    /** Approximate aim-offset in degrees before randomisation. */
    public double spread() {
        return spread;
    }

    /** Field-of-view magnification while aiming (1.0 = no zoom). */
    public double aimZoom() {
        return aimZoom;
    }

    public static GunType from(String name) {
        if (name == null) {
            return null;
        }
        for (GunType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }
}
