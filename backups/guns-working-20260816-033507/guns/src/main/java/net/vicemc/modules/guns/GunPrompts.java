package net.vicemc.modules.guns;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Turns the next chat message from a player into a callback so the arsenal
 * GUI can ask for free text (gun ids, display names, numbers) without leaving
 * the menu flow. Runs at LOWEST so prompted messages are consumed before the
 * phone's proximity chat filter.
 */
public final class GunPrompts implements Listener {

    private final GunsModule module;
    private final Map<UUID, BiConsumer<Player, String>> prompts = new ConcurrentHashMap<>();

    public GunPrompts(GunsModule module) {
        this.module = module;
    }

    public void prompt(Player player, String message, BiConsumer<Player, String> callback) {
        prompts.put(player.getUniqueId(), callback);
        player.closeInventory();
        module.context().notifications().msg(player, message);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        BiConsumer<Player, String> callback = prompts.get(player.getUniqueId());
        if (callback == null) {
            return;
        }
        prompts.remove(player.getUniqueId());
        event.setCancelled(true);
        final String message = event.getMessage();
        player.getScheduler().run(module.context().plugin(), task -> callback.accept(player, message), null);
    }
}
