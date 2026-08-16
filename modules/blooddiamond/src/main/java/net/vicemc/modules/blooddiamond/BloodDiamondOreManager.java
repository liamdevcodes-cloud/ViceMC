package net.vicemc.modules.blooddiamond;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blood Diamond ore placed with the Blood Diamond Wand. A placed ore is a
 * deepslate redstone ore block; after being mined it turns into plain
 * deepslate and regenerates back into ore after a short delay. Mining yields
 * a 10% chance for a Blood Diamond Shard and a 90% chance for a Red Gem.
 */
public final class BloodDiamondOreManager implements Listener {

    private enum Phase { ORE, DEPLETED }

    private static final NamespacedKey WAND_KEY = NamespacedKey.fromString("vicemc:bd_wand");

    private final ViceModuleContext ctx;
    private final YamlConfig config;
    private final BloodDiamondManager bd;
    private final JavaPlugin plugin;
    private final Random random = new Random();
    private final Map<String, Phase> ores = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPlace = new ConcurrentHashMap<>();

    public BloodDiamondOreManager(ViceModuleContext ctx, YamlConfig config,
                                  BloodDiamondManager bd, JavaPlugin plugin) {
        this.ctx = ctx;
        this.config = config;
        this.bd = bd;
        this.plugin = plugin;
    }

    public ItemStack wand() {
        Material material = Material.matchMaterial(config.getString("ore.wand.material", "BLAZE_ROD"));
        return ItemBuilder.of(material == null ? Material.BLAZE_ROD : material)
                .name(config.getString("ore.wand.name", "&4Blood Diamond Wand"))
                .modelData(config.getInt("ore.wand.model-data", 504))
                .lore(config.getStringList("ore.wand.lore").toArray(new String[0]))
                .tag(WAND_KEY, "true")
                .build();
    }

    public boolean isWand(ItemStack item) {
        return item != null && ItemBuilder.tag(item, WAND_KEY) != null;
    }

    public void giveWand(Player player) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(wand());
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
        }
        ctx.notifications().msg(player, "&aYou received a &4Blood Diamond Wand&a. Right-click to place ore.");
    }

    // --- Placement --------------------------------------------------------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!isWand(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!cooldownReady(player)) {
            return;
        }
        place(player, event.getClickedBlock(), event.getBlockFace());
    }

    private boolean cooldownReady(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastPlace.get(player.getUniqueId());
        if (last != null && now - last < 250L) {
            return false;
        }
        lastPlace.put(player.getUniqueId(), now);
        return true;
    }

    private void place(Player player, Block clicked, BlockFace face) {
        Block place;
        if (clicked == null || clicked.getType().isAir()) {
            place = player.getLocation().getBlock().getRelative(player.getFacing());
        } else if (isReplaceable(clicked.getType())) {
            place = clicked;
        } else {
            place = clicked.getRelative(face);
        }
        if (!isReplaceable(place.getType())) {
            ctx.notifications().warn(player, "&cYou can't place a Blood Diamond ore there.");
            return;
        }
        String key = key(place.getLocation());
        if (ores.containsKey(key)) {
            ctx.notifications().warn(player, "&cThere is already a Blood Diamond ore there.");
            return;
        }
        place.setType(oreMaterial());
        ores.put(key, Phase.ORE);
        ctx.notifications().msg(player, "&4Blood Diamond ore placed. Mine it for shards or red gems.");
    }

    // --- Mining -----------------------------------------------------------

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String key = key(block.getLocation());
        Phase phase = ores.get(key);
        if (phase == null) {
            return;
        }
        if (phase == Phase.DEPLETED) {
            ores.remove(key);
            return;
        }
        event.setCancelled(true);
        event.setDropItems(false);
        reward(event.getPlayer(), block.getLocation());
        block.setType(depletedMaterial());
        ores.put(key, Phase.DEPLETED);
        scheduleRegen(block.getWorld(), block.getLocation(), key);
    }

    private void reward(Player player, Location loc) {
        boolean gem = random.nextDouble() < config.getDouble("ore.reward.gem-chance", 0.9);
        int amount = gem
                ? Math.max(1, config.getInt("ore.reward.gem-amount", 1))
                : Math.max(1, config.getInt("ore.reward.shard-amount", 1));
        ItemStack item = gem ? bd.redGem() : bd.shard();
        item.setAmount(amount);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(loc, leftover.values().iterator().next());
        }
        if (gem) {
            ctx.notifications().msg(player, "&cYou mined a Red Gem!");
        } else {
            ctx.notifications().msg(player, "&4You found a Blood Diamond Shard!");
        }
    }

    // --- Regeneration -----------------------------------------------------

    private void scheduleRegen(World world, Location loc, String key) {
        Bukkit.getRegionScheduler().runDelayed(plugin, world,
                loc.getBlockX() >> 4, loc.getBlockZ() >> 4, task -> {
                    if (ores.get(key) != Phase.DEPLETED) {
                        return;
                    }
                    Block block = world.getBlockAt(loc);
                    if (block.getType() == depletedMaterial()) {
                        block.setType(oreMaterial());
                        ores.put(key, Phase.ORE);
                    } else {
                        ores.remove(key);
                    }
                }, regenSeconds() * 20L);
    }

    // --- Helpers ----------------------------------------------------------

    private boolean isReplaceable(Material type) {
        return type.isAir() || !type.isSolid();
    }

    private Material oreMaterial() {
        Material material = Material.matchMaterial(config.getString("ore.material", "DEEPSLATE_REDSTONE_ORE"));
        return material == null ? Material.DEEPSLATE_REDSTONE_ORE : material;
    }

    private Material depletedMaterial() {
        Material material = Material.matchMaterial(config.getString("ore.depleted-material", "DEEPSLATE"));
        return material == null ? Material.DEEPSLATE : material;
    }

    private int regenSeconds() {
        return Math.max(1, config.getInt("ore.regen-seconds", 30));
    }

    private String key(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }
}
