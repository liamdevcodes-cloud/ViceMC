package net.vicemc.modules.farm;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends a chat line when a player enters a farm so they know the crop, the
 * owner and the tax margin.
 */
public final class FarmPresenceListener implements Listener {

    private final FarmModule module;
    private final Map<UUID, String> current = new ConcurrentHashMap<>();

    public FarmPresenceListener(FarmModule module) {
        this.module = module;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!movedBlock(event)) {
            return;
        }
        Player player = event.getPlayer();
        FarmRegion region = module.manager().at(player.getLocation().getBlock());
        if (region == null) {
            current.remove(player.getUniqueId());
            return;
        }
        if (region.id.equals(current.get(player.getUniqueId()))) {
            return;
        }
        current.put(player.getUniqueId(), region.id);
        send(player, region);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        current.remove(event.getPlayer().getUniqueId());
    }

    private void send(Player player, FarmRegion region) {
        CropType crop = module.manager().cropFor(region);
        String cropName = crop == null ? "?" : crop.display();
        String owner = region.isPublic() ? "Public" : module.nameOf(region.owner);
        int tax = (int) Math.round(region.margin * 100);
        module.context().notifications().msg(player,
                "&2" + region.id + " &f" + cropName + " &8| &f" + owner + " &8| &eTax " + tax + "%");
    }

    private boolean movedBlock(PlayerMoveEvent event) {
        var from = event.getFrom();
        var to = event.getTo();
        return from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ();
    }
}
