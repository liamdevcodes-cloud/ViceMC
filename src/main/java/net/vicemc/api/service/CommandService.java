package net.vicemc.api.service;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Registers command handlers. The command must be declared in the plugin.yml
 * of the registering plugin.
 */
public interface CommandService {

    void register(JavaPlugin plugin, CommandSpec spec);
}
