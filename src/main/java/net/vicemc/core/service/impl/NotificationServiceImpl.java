package net.vicemc.core.service.impl;

import net.kyori.adventure.text.Component;
import net.vicemc.api.service.NotificationService;
import net.vicemc.api.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.function.BiConsumer;

/**
 * Player messaging with an optional Discord bridge.
 */
public final class NotificationServiceImpl implements NotificationService {

    private BiConsumer<String, String> discordSender;

    @Override
    public void msg(Player player, String message) {
        player.sendMessage(Text.color(message));
    }

    @Override
    public void action(Player player, String message) {
        player.sendActionBar(Text.color(message));
    }

    @Override
    public void broadcast(String message) {
        Bukkit.broadcast(Text.color(message));
    }

    @Override
    public void broadcast(String permission, String message) {
        Component component = Text.color(message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(permission)) {
                player.sendMessage(component);
            }
        }
    }

    @Override
    public void warn(Player player, String message) {
        player.sendMessage(Text.color("&c\u26A0 " + message));
    }

    @Override
    public void discord(String webhook, String message) {
        if (discordSender != null) {
            try {
                discordSender.accept(webhook, message);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void setDiscordSender(BiConsumer<String, String> sender) {
        this.discordSender = sender;
    }
}
