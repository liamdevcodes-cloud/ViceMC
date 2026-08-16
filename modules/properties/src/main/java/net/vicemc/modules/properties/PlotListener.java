package net.vicemc.modules.properties;

import net.vicemc.api.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the admin plot wand (left = corner 1, right = corner 2) and
 * protects every plot: only the owner, members and tenant may modify the
 * interior, players may only touch blocks they placed, and the structure that
 * was already there when the plot was bought can never be broken.
 */
public final class PlotListener implements Listener {

    private static final NamespacedKey WAND_KEY = NamespacedKey.fromString("vicemc:plot_wand");

    private final PropertiesModule module;
    private final PlotManager manager;
    private final Map<UUID, Location> firstCorner = new ConcurrentHashMap<>();

    public PlotListener(PropertiesModule module) {
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
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isWand(hand)) {
            return;
        }
        event.setCancelled(true);
        if (!player.hasPermission("vicemc.properties.admin")) {
            module.context().notifications().warn(player, "You do not have permission to use the plot wand.");
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            firstCorner.put(player.getUniqueId(), clicked.getLocation());
            module.context().notifications().msg(player, "&6Plot wand - corner 1 set: &f"
                    + clicked.getX() + ", " + clicked.getY() + ", " + clicked.getZ());
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Location a = firstCorner.remove(player.getUniqueId());
            if (a == null) {
                firstCorner.put(player.getUniqueId(), clicked.getLocation());
                module.context().notifications().msg(player, "&6Plot wand - corner 1 set: &f"
                        + clicked.getX() + ", " + clicked.getY() + ", " + clicked.getZ());
                return;
            }
            createPlot(player, a, clicked.getLocation());
        }
    }

    private void createPlot(Player player, Location a, Location b) {
        if (!a.getWorld().equals(b.getWorld())) {
            module.context().notifications().warn(player, "Both corners must be in the same world.");
            return;
        }
        PlotType type = manager.wandType();
        double value = manager.wandValue();
        Plot plot = manager.create(type, a.getWorld().getName(), a, b, value);
        if (plot == null) {
            module.context().notifications().warn(player, "That area overlaps an existing plot.");
            return;
        }
        module.context().notifications().msg(player, "&aCreated &f" + type.display() + " &e" + plot.serial
                + "&a worth &f" + net.vicemc.api.util.Text.moneyPlain(value)
                + "&a. (" + size(plot) + " blocks)");
        drawBorder(plot);
    }

    private String size(Plot plot) {
        long w = (long) plot.maxX - plot.minX + 1;
        long h = (long) plot.maxY - plot.minY + 1;
        long d = (long) plot.maxZ - plot.minZ + 1;
        return (w * h * d) + " m3";
    }

    private void drawBorder(Plot plot) {
        World world = Bukkit.getWorld(plot.world);
        if (world == null) {
            return;
        }
        Particle.DustOptions dust = new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 200, 0), 1f);
        int x1 = plot.minX, y1 = plot.minY, z1 = plot.minZ;
        int x2 = plot.maxX, y2 = plot.maxY, z2 = plot.maxZ;
        for (Player viewer : world.getPlayers()) {
            for (int y : new int[]{y1, y2}) {
                spawnRectFor(viewer, x1, y, z1, x2, z2, dust);
            }
            for (int z : new int[]{z1, z2}) {
                for (int y = y1; y <= y2; y++) {
                    spawnFor(viewer, x1, y, z, dust);
                    spawnFor(viewer, x2, y, z, dust);
                }
            }
        }
    }

    /** Shows the plot's edges only to the given player (border toggle). */
    public void showBorder(Plot plot, Player viewer) {
        World world = Bukkit.getWorld(plot.world);
        if (world == null) {
            return;
        }
        Particle.DustOptions dust = new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 200, 0), 1f);
        int x1 = plot.minX, y1 = plot.minY, z1 = plot.minZ;
        int x2 = plot.maxX, y2 = plot.maxY, z2 = plot.maxZ;
        for (int y : new int[]{y1, y2}) {
            spawnRectFor(viewer, x1, y, z1, x2, z2, dust);
        }
        for (int z : new int[]{z1, z2}) {
            for (int y = y1; y <= y2; y++) {
                spawnFor(viewer, x1, y, z, dust);
                spawnFor(viewer, x2, y, z, dust);
            }
        }
    }

    private void spawnRectFor(Player viewer, int x1, int y, int z1, int x2, int z2, Particle.DustOptions dust) {
        for (int x = x1; x <= x2; x++) {
            spawnFor(viewer, x, y, z1, dust);
            spawnFor(viewer, x, y, z2, dust);
        }
        for (int z = z1; z <= z2; z++) {
            spawnFor(viewer, x1, y, z, dust);
            spawnFor(viewer, x2, y, z, dust);
        }
    }

    private void spawnFor(Player viewer, int x, int y, int z, Particle.DustOptions dust) {
        viewer.spawnParticle(Particle.DUST, x + 0.5, y + 0.5, z + 0.5, 1, 0, 0, 0, 0, dust);
    }

    // --- Protection -------------------------------------------------------

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Plot plot = manager.at(block);
        if (plot == null) {
            return;
        }
        Player player = event.getPlayer();
        if (isAdmin(player)) {
            manager.unrecordPlaced(plot, block);
            return;
        }
        if (!canModify(plot, player)) {
            event.setCancelled(true);
            module.context().notifications().warn(player, "&cYou cannot build here.");
            return;
        }
        if (manager.isPlaced(plot, block)) {
            manager.unrecordPlaced(plot, block);
        } else {
            event.setCancelled(true);
            module.context().notifications().warn(player, "&cThat is part of the building - you cannot break it.");
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        Plot plot = manager.at(block);
        if (plot == null) {
            return;
        }
        Player player = event.getPlayer();
        boolean admin = isAdmin(player);
        if (admin) {
            manager.recordPlaced(plot, block, player.getUniqueId());
            return;
        }
        if (!canModify(plot, player)) {
            event.setCancelled(true);
            module.context().notifications().warn(player, "&cYou cannot build here.");
            return;
        }
        manager.recordPlaced(plot, block, player.getUniqueId());
    }

    @EventHandler
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        Plot plot = manager.at(event.getBlock());
        if (plot == null) {
            return;
        }
        Player player = event.getPlayer();
        if (isAdmin(player)) {
            return;
        }
        if (!canModify(plot, player)) {
            event.setCancelled(true);
            module.context().notifications().warn(player, "&cYou cannot build here.");
            return;
        }
        for (var state : event.getReplacedBlockStates()) {
            manager.recordPlaced(plot, state.getBlock(), player.getUniqueId());
        }
    }

    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block placed = event.getBlockClicked().getRelative(event.getBlockFace());
        Plot plot = manager.at(placed);
        if (plot == null) {
            return;
        }
        Player player = event.getPlayer();
        if (isAdmin(player)) {
            manager.recordPlaced(plot, placed, player.getUniqueId());
            return;
        }
        if (!canModify(plot, player)) {
            event.setCancelled(true);
            module.context().notifications().warn(player, "&cYou cannot place liquids here.");
        } else {
            manager.recordPlaced(plot, placed, player.getUniqueId());
        }
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        Block block = event.getBlock();
        Plot plot = manager.at(block);
        if (plot == null) {
            return;
        }
        Player player = event.getPlayer();
        if (isAdmin(player)) {
            manager.unrecordPlaced(plot, block);
            return;
        }
        if (!canModify(plot, player)) {
            event.setCancelled(true);
            module.context().notifications().warn(player, "&cYou cannot remove that here.");
        } else if (manager.isPlaced(plot, block)) {
            manager.unrecordPlaced(plot, block);
        } else {
            event.setCancelled(true);
            module.context().notifications().warn(player, "&cThat is part of the building.");
        }
    }

    @EventHandler
    public void onContainer(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!(block.getState() instanceof InventoryHolder)) {
            return;
        }
        Plot plot = manager.at(block);
        if (plot == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!isAdmin(player) && !manager.canAccess(plot, player.getUniqueId())) {
            event.setCancelled(true);
            module.context().notifications().warn(player, "&cThis storage belongs to someone else.");
        }
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> manager.at(block) != null);
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> manager.at(block) != null);
    }

    private boolean canModify(Plot plot, Player player) {
        if (plot.interiorLocked()) {
            return false;
        }
        return manager.canAccess(plot, player.getUniqueId());
    }

    private boolean isAdmin(Player player) {
        return player.hasPermission("vicemc.properties.admin");
    }
}
