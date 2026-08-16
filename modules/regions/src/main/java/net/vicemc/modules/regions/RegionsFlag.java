package net.vicemc.modules.regions;

import java.util.ArrayList;
import java.util.List;

/**
 * Enforceable zone flags. Each flag controls whether the named activity is
 * allowed inside a region. The set is deliberately comprehensive so a region
 * can be tuned from a fully safe town square to a wide-open war zone.
 */
public enum RegionsFlag {

    PVP("pvp", false),
    LOOT_DROP("loot-drop", false),
    ITEM_DROP("item-drop", true),
    WEAPONS("weapons", true),
    DAMAGE("damage", true),
    FLIGHT("flight", true),
    HUNGER("hunger", true),
    MOB_SPAWN("mob-spawn", true),
    BLOCK_BUILD("block-build", true),
    BLOCK_BREAK("block-break", true),
    TELEPORT("teleport", true),
    ENTER("enter", true),
    EXIT("exit", true),
    CHAT("chat", true),
    COMMANDS("commands", true);

    private final String key;
    private final boolean defaultValue;

    RegionsFlag(String key, boolean defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    public String key() {
        return key;
    }

    public boolean defaultValue() {
        return defaultValue;
    }

    public String description() {
        return switch (this) {
            case PVP -> "Player vs player combat is allowed.";
            case LOOT_DROP -> "Players who die inside drop their inventory.";
            case ITEM_DROP -> "Players can drop and throw items inside.";
            case WEAPONS -> "Players can use weapons and attack.";
            case DAMAGE -> "Players take natural damage (fall, fire, drowning).";
            case FLIGHT -> "Players can toggle flight inside.";
            case HUNGER -> "Hunger drains naturally inside.";
            case MOB_SPAWN -> "Hostile mobs spawn naturally inside.";
            case BLOCK_BUILD -> "Players can place blocks inside.";
            case BLOCK_BREAK -> "Players can break blocks inside.";
            case TELEPORT -> "Players can teleport into the region.";
            case ENTER -> "Players can walk into the region.";
            case EXIT -> "Players can walk out of the region.";
            case CHAT -> "Players can send chat messages inside.";
            case COMMANDS -> "Players can run commands inside.";
        };
    }

    public static RegionsFlag from(String key) {
        for (RegionsFlag flag : values()) {
            if (flag.key.equalsIgnoreCase(key)) {
                return flag;
            }
        }
        return null;
    }

    public static List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (RegionsFlag flag : values()) {
            keys.add(flag.key);
        }
        return keys;
    }

    public static java.util.Map<String, Boolean> defaults() {
        java.util.Map<String, Boolean> map = new java.util.LinkedHashMap<>();
        for (RegionsFlag flag : values()) {
            map.put(flag.key, flag.defaultValue);
        }
        return map;
    }
}
