package net.vicemc.core.listener;

import net.vicemc.api.service.BuildProtectionService;
import net.vicemc.api.util.Text;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protects the map from being built on or broken. Runs first (LOWEST): if the
 * player has builder mode on or an admin bypass, everything proceeds. Blocks
 * claimed by a module (owned plot interiors, farm crops) are left to that
 * module's own protection. Everything else is cancelled so the map cannot be
 * griefed.
 */
public final class BuildProtectionListener implements Listener {

    private static final String BYPASS = "vicemc.buildermode.bypass";
    private static final String ADMIN = "vicemc.admin";

    private final BuildProtectionService protection;
    private final Map<UUID, Long> lastWarn = new ConcurrentHashMap<>();

    public BuildProtectionListener(BuildProtectionService protection) {
        this.protection = protection;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (isAllowed(event.getPlayer(), event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        warn(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (isAllowed(event.getPlayer(), event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        warn(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        if (isAllowed(event.getPlayer(), event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        warn(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (event.getBlockClicked() == null) {
            return;
        }
        Block placed = event.getBlockClicked().getRelative(event.getBlockFace());
        if (isAllowed(event.getPlayer(), placed)) {
            return;
        }
        event.setCancelled(true);
        warn(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (isAllowed(event.getPlayer(), event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        warn(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTrample(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (isAllowed(player, event.getBlock())) {
            return;
        }
        event.setCancelled(true);
        warn(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> !protection.isManaged(block));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> !protection.isManaged(block));
    }

    private boolean isAllowed(Player player, Block block) {
        if (player.hasPermission(BYPASS) || player.hasPermission(ADMIN)) {
            return true;
        }
        if (protection.builderMode(player.getUniqueId())) {
            return true;
        }
        return protection.isManaged(block);
    }

    private void warn(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(player.getUniqueId());
        if (last != null && now - last < 3000) {
            return;
        }
        lastWarn.put(player.getUniqueId(), now);
        player.sendMessage(Text.color("&cProtected map. &e/buildermode&c to build here."));
    }
}
