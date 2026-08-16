package net.vicemc.modules.staff;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Runtime enforcement of moderation states: banned players cannot join,
 * muted players cannot talk, frozen players cannot move, vanished staff are
 * hidden from everyone who is not staff, and spectate sessions clean up when
 * either side leaves. Registered after {@link StaffPrompts} so prompted chat
 * messages are consumed first.
 */
public final class StaffListener implements Listener {

    private final StaffModule module;

    public StaffListener(StaffModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        module.activeBan(event.getUniqueId()).ifPresent(ban -> {
            String message = "You are banned from ViceMC.\nReason: " + ban.reason()
                    + "\nExpires: " + ban.durationLabel(System.currentTimeMillis())
                    + "\nBanned by: " + ban.actor();
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, message);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Player player = event.getPlayer();
        module.muteData(player.getUniqueId()).ifPresent(mute -> {
            event.setCancelled(true);
            module.context().notifications().warn(player,
                    "&cYou are muted&7 (" + mute.remainingLabel(System.currentTimeMillis())
                            + "). Reason: &f" + mute.reason());
        });
        if (!event.isCancelled() && module.staffChatOn(player.getUniqueId())) {
            event.setCancelled(true);
            module.staffChat(player, event.getMessage());
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!module.isFrozen(event.getPlayer().getUniqueId())) {
            return;
        }
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to != null && from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }
        event.setCancelled(true);
        player.teleport(from);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        module.trackStaff(joined);
        if (module.isVanished(joined.getUniqueId())) {
            module.applyVanish(joined);
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (module.isVanished(online.getUniqueId())) {
                joined.hidePlayer(online);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player left = event.getPlayer();
        module.untrackStaff(left);
        module.unfreeze(left.getUniqueId());
        module.unspectate(left.getUniqueId());
        module.cancelSpectateOf(left.getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        module.unfreeze(event.getEntity().getUniqueId());
    }
}
