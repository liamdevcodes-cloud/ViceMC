package net.vicemc.modules.farm;

/**
 * A stack of harvested crops carrying its farm provenance. The owner id/name
 * and the tax percentage are snapshots taken at harvest time, so the lore on
 * the stored item (and the cut paid out at sale time) always matches the farm
 * the crop actually came from, even if the farm changes owner or margin later.
 */
public final class StoredCrop {

    public String crop = "WHEAT";
    public int amount = 0;
    public String farmId = "";
    public String farmOwnerId = "";
    public String farmOwnerName = "";
    /** 0-100, the share of the sale value the farm owner keeps. */
    public int taxPercent = 0;
}
