package net.vicemc.modules.guns;

/**
 * The ammunition families. Each {@link GunType} is fed by exactly one of
 * these; the id string is what gets stamped on the ammo item and matched
 * during reloads.
 */
public enum AmmoType {

    PISTOL("pistol", "9mm Rounds"),
    SMG("smg", "SMG Rounds"),
    RIFLE("rifle", "Rifle Rounds"),
    SHOTGUN("shell", "Shotgun Shells"),
    SNIPER("sniper", "Sniper Rounds");

    private final String id;
    private final String display;

    AmmoType(String id, String display) {
        this.id = id;
        this.display = display;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display;
    }

    public static AmmoType byId(String id) {
        if (id == null) {
            return null;
        }
        for (AmmoType type : values()) {
            if (type.id.equalsIgnoreCase(id)) {
                return type;
            }
        }
        return null;
    }

    public static AmmoType from(String name) {
        if (name == null) {
            return null;
        }
        for (AmmoType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }
}
