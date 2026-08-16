package net.vicemc.modules.properties;

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
 * Turns the next chat message from a player into a callback, so GUIs can ask
 * for free text (sale prices, rent periods, member names) without leaving the
 * GUI-driven flow. Typing "cancel" aborts the prompt.
 */
public final class PromptManager implements Listener {

    private final PropertiesModule module;
    private final Map<UUID, BiConsumer<Player, String>> prompts = new ConcurrentHashMap<>();

    public PromptManager(PropertiesModule module) {
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
