package net.vicemc.modules.staff;

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
 * Turns the next chat message from a staff member into a callback so the
 * staff panel can ask for free text (kick/ban/mute reasons) without leaving
 * the menu flow. Typing "cancel" aborts the prompt. Registered before the
 * runtime listener so prompted messages are consumed before staff chat
 * handling sees them.
 */
public final class StaffPrompts implements Listener {

    private final StaffModule module;
    private final Map<UUID, BiConsumer<Player, String>> prompts = new ConcurrentHashMap<>();

    public StaffPrompts(StaffModule module) {
        this.module = module;
    }

    public boolean pending(Player player) {
        return prompts.containsKey(player.getUniqueId());
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
