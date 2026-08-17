package net.vicemc.modules.gangs;

/**
 * A contested street zone the two gangs fight over with stand-and-hold
 * capture. Ownership is runtime state and persists across restarts.
 * During war events, capturing a territory yields rewards.
 */
public final class Territory {

    public String id;
    public String name;
    public String regionTag;
    public int captureSeconds;

    /** Reward money given to the capturing gang. */
    public double rewardMoney;

    /** Number of gun items given as reward on capture. */
    public int rewardGuns;

    /** The gang that currently owns this zone, or empty for neutral. */
    public String owner = "";

    /** Capture progress 0..100 toward the gang currently filling the bar. */
    public int progress;

    /** The gang currently filling the capture bar, or empty. */
    public String progressOwner = "";
}
