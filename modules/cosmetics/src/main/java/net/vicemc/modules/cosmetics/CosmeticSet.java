package net.vicemc.modules.cosmetics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A cosmetic armor set: name, anonymity flag and per-slot pieces.
 */
public final class CosmeticSet {

    public String id;
    public String name;
    public boolean mask;
    public Map<String, CosmeticPiece> pieces = new LinkedHashMap<>();
}
