package net.vicemc.modules.phone;

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
 * Turns the next chat message from a player into a callback so the phone can
 * ask for free text (plot serials, far chat messages) without leaving the
 * menu flow. Runs at LOWEST so prompted messages are consumed before the
 * proximity chat filter and before any other chat handling.
 */
public final class PhonePrompts implements Listener {

    private final PhoneModule module;
    private final Map<UUID, BiConsumer<Player, String>> prompts = new ConcurrentHashMap<>();

    public PhonePrompts(PhoneModule module) {
        this.module = module;
    }

    public void prompt(Player player, String message, BiConsumer<Player, String> callback) {
        prompts.put(player.getUniqueId(), callback);
        player.closeInventory();
        module.context().notifications().msg(player, message);
    }

    public void cancel(Player player) {
        prompts.remove(player.getUniqueId());
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
