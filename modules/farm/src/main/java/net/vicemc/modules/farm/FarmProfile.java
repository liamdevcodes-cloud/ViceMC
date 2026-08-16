package net.vicemc.modules.farm;

import java.util.ArrayList;
import java.util.List;

/**
 * A player's farming stats (XP/level) and the crops they harvested, stored in
 * the /farm inventory. Persisted as one JSON blob per player.
 */
public final class FarmProfile {

    public double xp = 0;
    public int level = 1;
    public List<StoredCrop> crops = new ArrayList<>();
}
