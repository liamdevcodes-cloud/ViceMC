package net.vicemc.api.service;

import org.bukkit.entity.Player;

import java.util.function.BiConsumer;

/**
 * Player messaging plus an optional Discord bridge installed by the Discord
 * module.
 */
public interface NotificationService {

    void msg(Player player, String message);

    void action(Player player, String message);

    void broadcast(String message);

    void broadcast(String permission, String message);

    void warn(Player player, String message);

    void discord(String webhook, String message);

    void setDiscordSender(BiConsumer<String, String> sender);
}
