package net.vicemc.core.service.impl;

import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandService;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.Text;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

/**
 * Binds {@link CommandSpec}s to the commands declared in plugin.yml.
 */
public final class CommandServiceImpl implements CommandService {

    @Override
    public void register(JavaPlugin plugin, CommandSpec spec) {
        PluginCommand command = plugin.getCommand(spec.name);
        if (command == null) {
            plugin.getLogger().warning("Command '" + spec.name + "' is not declared in plugin.yml");
            return;
        }
        command.setAliases(spec.aliases);
        command.setDescription(spec.description);
        if (!spec.usage.isEmpty()) {
            command.setUsage(spec.usage);
        }
        if (spec.permission != null) {
            command.setPermission(spec.permission);
        }
        command.setExecutor((sender, cmd, label, args) -> {
            try {
                spec.executor.accept(new CommandContext(sender, label, args));
            } catch (Exception ex) {
                sender.sendMessage(Text.color("&cCommand error: " + ex.getMessage()));
                plugin.getLogger().log(Level.WARNING, "Error executing /" + spec.name, ex);
            }
            return true;
        });
        command.setTabCompleter((sender, cmd, label, args) ->
                spec.tab.apply(new CommandContext(sender, label, args), Arrays.asList(args)));
    }
}
