package net.vicemc.modules.lifestyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Definition of a custom food or drink loaded from the items section of
 * lifestyle.yml. Public fields match the config schema (same style as the
 * other ViceMC data classes).
 */
public final class LifestyleItem {

    public String id;
    public String kind = "FOOD";
    public String material = "APPLE";
    public int modelData;
    public String name = "&eNew Item";
    public List<String> lore = new ArrayList<>();
    public int hunger;
    public double saturation;
    public double thirstRestore;
    public double restRestore;
    public double fitness;
    public double price;
    public boolean leavesEmptyBottle;

    public boolean isDrink() {
        return "DRINK".equalsIgnoreCase(kind);
    }
}
