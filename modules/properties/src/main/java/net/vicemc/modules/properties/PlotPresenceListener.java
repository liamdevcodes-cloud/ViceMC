package net.vicemc.modules.properties;

import net.vicemc.api.util.GuiKit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends a chat line when a player enters a plot so they know what it is
 * (house, factory, ...) and who owns it.
 */
public final class PlotPresenceListener implements Listener {

    private final PropertiesModule module;
    private final Map<UUID, String> current = new ConcurrentHashMap<>();

    public PlotPresenceListener(PropertiesModule module) {
        this.module = module;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!movedBlock(event)) {
            return;
        }
        Player player = event.getPlayer();
        Plot plot = module.manager().at(player.getLocation().getBlock());
        if (plot == null) {
            current.remove(player.getUniqueId());
            return;
        }
        if (plot.serial.equals(current.get(player.getUniqueId()))) {
            return;
        }
        current.put(player.getUniqueId(), plot.serial);
        send(player, plot);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        current.remove(event.getPlayer().getUniqueId());
    }

    private void send(Player player, Plot plot) {
        String owner = plot.owned() ? module.nameOf(plot.owner) : "&7unowned";
        String status;
        if (plot.isRented()) {
            status = "&brented";
        } else if (plot.forSale) {
            status = "&6for sale &f" + GuiKit.fmt(plot.salePrice);
        } else if (plot.forRent) {
            status = "&bfor rent &f" + GuiKit.fmt(plot.rentPrice) + "&b/" + plot.rentDays + "d";
        } else if (!plot.owned()) {
            status = "&afor sale &f" + GuiKit.fmt(plot.price);
        } else {
            status = "&aowned";
        }
        String id = player.getUniqueId().toString();
        String role;
        if (id.equals(plot.owner)) {
            role = "&aowner";
        } else if (plot.members.contains(id)) {
            role = "&bmember";
        } else if (id.equals(plot.tenant)) {
            role = "&dtenant";
        } else {
            role = null;
        }
        String line = "&e" + plot.serial + " &f" + plot.type().display()
                + " &8| " + owner + " &8| " + status;
        if (role != null) {
            line += " &8| &f" + role;
        }
        module.context().notifications().msg(player, line);
        if (module.borderEnabled(player.getUniqueId())) {
            module.showBorder(plot, player);
        }
    }

    private boolean movedBlock(PlayerMoveEvent event) {
        var from = event.getFrom();
        var to = event.getTo();
        return from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ();
    }
}
