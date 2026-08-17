package net.vicemc.modules.gangs;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A contested zone. Everything except owner/progress is loaded from config
 * so admins can fully customize capture time, rewards, and reward items.
 */
public final class Territory {

    public String id;
    public String name;
    public String regionTag;
    public int captureSeconds;

    /** Money deposited into the capturing gang's bank. */
    public double rewardMoney;

    /** Money deposited into the gang bank per day this territory is held. */
    public double weeklyRewardMoney;

    /** Configurable reward items given on capture (admin-defined in gangs.yml). */
    public final List<ItemStack> rewardItems = new ArrayList<>();

    /** The gang that currently owns this zone, or empty for neutral. */
    public String owner = "";

    /** Capture progress 0..100 toward the gang currently filling the bar. */
    public int progress;

    /** The gang currently filling the capture bar, or empty. */
    public String progressOwner = "";

    // ---- Admin runtime mutation (for /gang admin createterritory) ----

    public static Territory create(String id, String name, String regionTag, int captureSeconds,
                                   double rewardMoney, double weeklyRewardMoney) {
        Territory t = new Territory();
        t.id = id;
        t.name = name;
        t.regionTag = regionTag;
        t.captureSeconds = captureSeconds;
        t.rewardMoney = rewardMoney;
        t.weeklyRewardMoney = weeklyRewardMoney;
        return t;
    }
}
