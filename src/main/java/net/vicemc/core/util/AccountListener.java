package net.vicemc.core.util;

import net.vicemc.api.service.EconomyService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Ensures every player has a ledger account on first join.
 */
public final class AccountListener implements Listener {

    private final EconomyService economy;

    public AccountListener(EconomyService economy) {
        this.economy = economy;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        economy.balance(event.getPlayer().getUniqueId());
    }
}
