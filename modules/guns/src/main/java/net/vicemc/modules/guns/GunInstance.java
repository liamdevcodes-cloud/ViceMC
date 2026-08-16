package net.vicemc.modules.guns;

/**
 * One physical gun: a definition reference plus the per-item state carried in
 * the item's persistent tags - the unique serial number, the current
 * durability and how many rounds are loaded in the magazine.
 *
 * @param def the definition this gun was made from, or null if the recipe was
 *            deleted (the item still exists but can no longer be fired)
 * @param serial the unique serial, e.g. VIC-A3F9KQ
 * @param durability remaining durability; zero means the gun is broken
 * @param ammoInMag rounds currently loaded
 */
public record GunInstance(GunDefinition def, String serial, int durability, int ammoInMag) {

    public boolean broken() {
        return durability <= 0;
    }

    public boolean defOk() {
        return def != null;
    }
}
