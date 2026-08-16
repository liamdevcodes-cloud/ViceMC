package net.vicemc.modules.regions;

/**
 * Chat indicator safety level derived from a region's pvp and loot-drop
 * flags: SAFE (no combat, no loot loss), DANGER (combat and loot loss),
 * NEUTRAL (anything in between).
 */
public enum Safety {
    SAFE("&aSAFE"),
    DANGER("&cDANGER"),
    NEUTRAL("&eNEUTRAL");

    private final String label;

    Safety(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
