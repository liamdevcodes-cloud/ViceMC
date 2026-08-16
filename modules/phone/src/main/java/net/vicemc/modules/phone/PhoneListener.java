package net.vicemc.modules.phone;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps the phone permanently in the far-right hotbar slot (8), opens the
 * phone GUI on right-click, and filters normal in-game chat to a proximity
 * range so you only see the players who are close enough to hear you.
 */
public final class PhoneListener implements Listener {

    private final PhoneModule module;

    public PhoneListener(PhoneModule module) {
        this.module = module;
    }

    // --- Hotbar slot 8 enforcement ---------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        module.ensurePhone(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        module.ensurePhone(event.getPlayer());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ClickType click = event.getClick();
        if (click == ClickType.NUMBER_KEY && (event.getSlot() == 8 || event.getHotbarButton() == 8)) {
            event.setCancelled(true);
            return;
        }
        if (click == ClickType.SWAP_OFFHAND
                && (event.getSlot() == 8 || module.isPhone(player.getInventory().getItemInOffHand()))) {
            event.setCancelled(true);
            return;
        }
        if (event.getClickedInventory() instanceof PlayerInventory) {
            if (event.getSlot() == 8) {
                event.setCancelled(true);
                return;
            }
            if (module.isPhone(event.getCurrentItem())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (module.isPhone(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (module.isPhone(event.getMainHandItem()) || module.isPhone(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    // --- Death drops ------------------------------------------------------

    /**
     * The phone never drops on death. Outside the designated death-drop
     * region (default: the Blood Diamond Mine) nothing drops at all - the
     * player keeps their full inventory and experience.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(module::isPhone);
        String tag = module.config().getString("death-drop-region-tag", "blooddiamond");
        if (!module.context().regions().isInside(event.getEntity().getLocation(), tag)) {
            event.getDrops().clear();
            event.setKeepInventory(true);
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
    }

    // --- Opening the phone ------------------------------------------------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!module.isPhone(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        module.gui().openPhone(event.getPlayer());
    }

    // --- Proximity chat ---------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncPlayerChatEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Player sender = event.getPlayer();
        int range = module.chatRange();
        int rangeSq = range * range;
        Set<Player> recipients = new HashSet<>();
        var senderLoc = sender.getLocation();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.getWorld().equals(sender.getWorld())) {
                continue;
            }
            var loc = online.getLocation();
            double dx = loc.getX() - senderLoc.getX();
            double dz = loc.getZ() - senderLoc.getZ();
            if (dx * dx + dz * dz <= rangeSq) {
                recipients.add(online);
            }
        }
        event.getRecipients().clear();
        event.getRecipients().addAll(recipients);
    }
}
