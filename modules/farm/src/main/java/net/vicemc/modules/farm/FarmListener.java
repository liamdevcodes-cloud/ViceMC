package net.vicemc.modules.farm;

import net.vicemc.api.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The admin farm wand (left = corner 1, right = corner 2) and the harvesting
 * rules: breaking a mature crop of the region's type harvests it into the
 * /farm inventory and resets the plant to regrow, immature crops cannot be
 * broken, and crops above the player's farm level are blocked. Everyone -
 * admins included - farms through this flow; admins can sneak-break a crop to
 * physically clear it instead of harvesting.
 */
public final class FarmListener implements Listener {

    private static final NamespacedKey WAND_KEY = NamespacedKey.fromString("vicemc:farm_wand");

    private final FarmModule module;
    private final FarmManager manager;
    private final Map<UUID, Location> firstCorner = new ConcurrentHashMap<>();
    private final Map<String, ScheduledTask> regenTasks = new ConcurrentHashMap<>();

    public FarmListener(FarmModule module) {
        this.module = module;
        this.manager = module.manager();
    }

    public static boolean isWand(ItemStack item) {
        return item != null && ItemBuilder.hasTag(item, WAND_KEY, "true");
    }

    public static NamespacedKey wandKey() {
        return WAND_KEY;
    }

    // --- Admin wand -------------------------------------------------------

    @EventHandler
    public void onWand(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isWand(hand)) {
            return;
        }
        event.setCancelled(true);
        if (!player.hasPermission("vicemc.farm.admin")) {
            module.context().notifications().warn(player, "You do not have permission to use the farm wand.");
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            firstCorner.put(player.getUniqueId(), clicked.getLocation());
            module.context().notifications().msg(player, "&2Farm wand - corner 1 set: &f"
                    + clicked.getX() + ", " + clicked.getY() + ", " + clicked.getZ());
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Location a = firstCorner.remove(player.getUniqueId());
            if (a == null) {
                firstCorner.put(player.getUniqueId(), clicked.getLocation());
                module.context().notifications().msg(player, "&2Farm wand - corner 1 set: &f"
                        + clicked.getX() + ", " + clicked.getY() + ", " + clicked.getZ());
                return;
            }
            createRegion(player, a, clicked.getLocation());
        }
    }

    private void createRegion(Player player, Location a, Location b) {
        if (!a.getWorld().equals(b.getWorld())) {
            module.context().notifications().warn(player, "Both corners must be in the same world.");
            return;
        }
        CropType crop = manager.wandCrop();
        if (crop == null) {
            module.context().notifications().warn(player, "No crops are configured - add crops to farming.yml.");
            return;
        }
        FarmRegion region = manager.createRegion(crop, a, b);
        if (region == null) {
            module.context().notifications().warn(player, "That area overlaps an existing farm region.");
            return;
        }
        module.context().notifications().msg(player, "&aCreated &f" + crop.display() + "&a farm &e"
                + region.id + "&a. (" + size(region) + " blocks)");
    }

    private String size(FarmRegion region) {
        long w = (long) region.maxX - region.minX + 1;
        long h = (long) region.maxY - region.minY + 1;
        long d = (long) region.maxZ - region.minZ + 1;
        return (w * h * d) + " m3";
    }

    // --- Harvesting -------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Block block = event.getBlock();
        FarmRegion region = manager.at(block);
        if (region == null) {
            return;
        }
        CropType crop = manager.cropFor(region);
        if (crop == null || block.getType() != crop.block) {
            return;
        }
        Player player = event.getPlayer();
        if (isAdmin(player) && player.isSneaking()) {
            cancelRegen(block);
            module.context().notifications().msg(player, "&7Sneak-break cleared the crop without harvesting.");
            return;
        }
        event.setCancelled(true);
        event.setDropItems(false);
        if (!manager.canHarvest(player, crop)) {
            module.context().notifications().warn(player, "&cYou need Farm Level &f" + crop.level
                    + "&c to harvest " + crop.display() + "&c.");
            return;
        }
        if (!manager.isMature(block, crop)) {
            module.context().notifications().warn(player, "&cThis crop is still growing - come back later.");
            return;
        }
        manager.resetCrop(block, crop);
        scheduleRegen(block, crop);
        manager.harvest(player, region, crop);
    }

    private boolean isAdmin(Player player) {
        return player.hasPermission("vicemc.farm.admin");
    }

    // --- Regeneration -----------------------------------------------------

    private void scheduleRegen(Block block, CropType crop) {
        String key = key(block.getLocation());
        ScheduledTask old = regenTasks.remove(key);
        if (old != null) {
            cancelQuietly(old);
        }
        World world = block.getWorld();
        Location loc = block.getLocation();
        Material material = crop.block;
        ScheduledTask task = Bukkit.getRegionScheduler().runDelayed(module.context().plugin(), world,
                loc.getBlockX() >> 4, loc.getBlockZ() >> 4, scheduled -> {
                    regenTasks.remove(key);
                    Block current = world.getBlockAt(loc);
                    if (current.getType() != material) {
                        return;
                    }
                    BlockData data = current.getBlockData();
                    if (data instanceof Ageable ageable) {
                        ageable.setAge(ageable.getMaximumAge());
                        current.setBlockData(data);
                    }
                }, crop.regenSeconds * 20L);
        regenTasks.put(key, task);
    }

    private void cancelRegen(Block block) {
        String key = key(block.getLocation());
        ScheduledTask task = regenTasks.remove(key);
        if (task != null) {
            cancelQuietly(task);
        }
    }

    private boolean isTrackedCrop(Block block) {
        return regenTasks.containsKey(key(block.getLocation()));
    }

    // --- Explosion protection ---------------------------------------------

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    /** Crops in a farm region (regrowing or mature) cannot be blown up. */
    private boolean isProtected(Block block) {
        if (isTrackedCrop(block)) {
            return true;
        }
        FarmRegion region = manager.at(block);
        if (region == null) {
            return false;
        }
        CropType crop = manager.cropFor(region);
        return crop != null && block.getType() == crop.block;
    }

    private String key(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    public void shutdown() {
        for (ScheduledTask task : regenTasks.values()) {
            cancelQuietly(task);
        }
        regenTasks.clear();
    }

    private static void cancelQuietly(ScheduledTask task) {
        try {
            task.cancel();
        } catch (IllegalStateException ignored) {
        }
    }
}
