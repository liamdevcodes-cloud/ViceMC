package net.vicemc.modules.blooddiamond;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

/**
 * Blood Diamond shards, crafted Blood Diamonds and non-withdrawable store
 * credit tracking.
 */
public final class BloodDiamondManager {

    private static final NamespacedKey SHARD_KEY = NamespacedKey.fromString("vicemc:bd_shard");
    private static final NamespacedKey DIAMOND_KEY = NamespacedKey.fromString("vicemc:bd_diamond");
    private static final NamespacedKey GEM_KEY = NamespacedKey.fromString("vicemc:bd_gem");

    private final ViceModuleContext ctx;
    private final YamlConfig config;
    private final int shardsPerDiamond;

    public BloodDiamondManager(ViceModuleContext ctx, YamlConfig config) {
        this.ctx = ctx;
        this.config = config;
        this.shardsPerDiamond = Math.max(1, config.getInt("shards-per-diamond", 8));
    }

    public int shardsPerDiamond() {
        return shardsPerDiamond;
    }

    public ItemStack shard() {
        Material material = Material.matchMaterial(config.getString("shard.material", "PRISMARINE_SHARD"));
        return ItemBuilder.of(material == null ? Material.PRISMARINE_SHARD : material)
                .name(config.getString("shard.name", "&4Blood Diamond Shard"))
                .modelData(config.getInt("shard.model-data", 501))
                .lore(config.getStringList("shard.lore").toArray(new String[0]))
                .tag(SHARD_KEY, "true")
                .build();
    }

    public ItemStack bloodDiamond() {
        Material material = Material.matchMaterial(config.getString("blood-diamond.material", "DIAMOND"));
        return ItemBuilder.of(material == null ? Material.DIAMOND : material)
                .name(config.getString("blood-diamond.name", "&4Blood Diamond"))
                .modelData(config.getInt("blood-diamond.model-data", 500))
                .lore(config.getStringList("blood-diamond.lore").toArray(new String[0]))
                .tag(DIAMOND_KEY, "true")
                .build();
    }

    public ItemStack redGem() {
        Material material = Material.matchMaterial(config.getString("gem.material", "REDSTONE"));
        return ItemBuilder.of(material == null ? Material.REDSTONE : material)
                .name(config.getString("gem.name", "&cRed Gem"))
                .modelData(config.getInt("gem.model-data", 503))
                .lore(config.getStringList("gem.lore").toArray(new String[0]))
                .tag(GEM_KEY, "true")
                .build();
    }

    public static boolean isShard(ItemStack item) {
        return item != null && ItemBuilder.tag(item, SHARD_KEY) != null;
    }

    public static boolean isBloodDiamond(ItemStack item) {
        return item != null && ItemBuilder.tag(item, DIAMOND_KEY) != null;
    }

    public static boolean isRedGem(ItemStack item) {
        return item != null && ItemBuilder.tag(item, GEM_KEY) != null;
    }

    // --- Store credit (non-withdrawable) ----------------------------------

    public double credit(UUID player) {
        return ctx.storage().getModuleData("blooddiamond", "credit:" + player)
                .map(Double::parseDouble).orElse(0.0);
    }

    public void addCredit(UUID player, double amount) {
        ctx.storage().setModuleData("blooddiamond", "credit:" + player,
                String.valueOf(credit(player) + amount));
    }

    // --- RMT flags --------------------------------------------------------

    public void flagRmt(UUID player, String reason) {
        ctx.storage().setModuleData("blooddiamond", "rmt:" + player,
                reason == null ? "real-money trading" : reason);
    }

    public Map<String, String> rmtFlags() {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : ctx.storage().moduleDataAll("blooddiamond").entrySet()) {
            if (entry.getKey().startsWith("rmt:")) {
                result.put(entry.getKey().substring(4), entry.getValue());
            }
        }
        return result;
    }

    public java.util.List<String> flagKeys() {
        return new ArrayList<>(rmtFlags().keySet());
    }
}
