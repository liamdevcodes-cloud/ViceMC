package net.vicemc.modules.guns;

import org.bukkit.Material;

/**
 * A saved gun "recipe". Admins create these in the arsenal GUI: give them an
 * id, a display name, a gun type and a skin (any held item's material + model
 * data). Every spawned instance of the definition gets its own serial number,
 * durability and magazine - see {@link GunInstance}.
 *
 * The class is a mutable POJO on purpose: it is (de)serialised with Gson
 * straight into module storage.
 */
public final class GunDefinition {

    public String id = "";
    public String name = "&fGun";
    public String type = GunType.HANDGUN.name();
    public String material = Material.DROPPER.name();
    public int modelData;
    public double damage = 6.0;
    public int magSize = 12;
    public double fireRate = 3.0;
    public int range = 60;
    public int durability = 300;
    public int pellets = 1;
    public double reloadSeconds = 1.5;
    public double spread = 3.0;
    /** Custom fire sound (e.g. "sounds:glock18" or "sounds:glock18|1.0|1.0"). Empty = gun type default. */
    public String sound = "";
    /** Custom reload sound. Empty = the global reload sound. */
    public String reloadSound = "";
    /** Whether this gun uses the charged-crossbow third-person pose. Null keeps legacy AK behavior. */
    public Boolean thirdPersonPose;

    public boolean thirdPersonPoseEnabled() {
        return thirdPersonPose != null ? thirdPersonPose : id.equalsIgnoreCase("ak-74") || id.equalsIgnoreCase("ak74");
    }

    public GunType gunType() {
        GunType t = GunType.from(type);
        return t != null ? t : GunType.HANDGUN;
    }

    public Material bukkitMaterial() {
        try {
            return Material.valueOf(material);
        } catch (IllegalArgumentException ex) {
            return Material.DROPPER;
        }
    }

    /** Applies a gun type's defaults to this definition (keeps id, name and skin). */
    public void applyType(GunType gunType) {
        this.type = gunType.name();
        this.damage = gunType.damage();
        this.magSize = gunType.magSize();
        this.fireRate = gunType.fireRate();
        this.range = gunType.range();
        this.durability = gunType.durability();
        this.pellets = gunType.pellets();
        this.reloadSeconds = gunType.reloadSeconds();
        this.spread = gunType.spread();
    }

    public GunDefinition copy() {
        GunDefinition copy = new GunDefinition();
        copy.id = id;
        copy.name = name;
        copy.type = type;
        copy.material = material;
        copy.modelData = modelData;
        copy.damage = damage;
        copy.magSize = magSize;
        copy.fireRate = fireRate;
        copy.range = range;
        copy.durability = durability;
        copy.pellets = pellets;
        copy.reloadSeconds = reloadSeconds;
        copy.spread = spread;
        copy.sound = sound;
        copy.reloadSound = reloadSound;
        copy.thirdPersonPose = thirdPersonPose;
        return copy;
    }
}
