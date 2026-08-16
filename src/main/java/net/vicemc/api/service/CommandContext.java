package net.vicemc.api.service;

import net.vicemc.api.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Mutable command invocation context passed to every command handler.
 */
public final class CommandContext {

    private final CommandSender sender;
    private final String label;
    private final String[] args;

    public CommandContext(CommandSender sender, String label, String[] args) {
        this.sender = sender;
        this.label = label;
        this.args = args;
    }

    public CommandSender sender() {
        return sender;
    }

    public String label() {
        return label;
    }

    public String[] args() {
        return args;
    }

    public boolean isPlayer() {
        return sender instanceof Player;
    }

    public Player player() {
        return sender instanceof Player p ? p : null;
    }

    public int size() {
        return args.length;
    }

    public String arg(int index) {
        return index < args.length ? args[index] : "";
    }

    public String arg(int index, String def) {
        return index < args.length && !args[index].isEmpty() ? args[index] : def;
    }

    public int argInt(int index, int def) {
        try {
            return index < args.length ? Integer.parseInt(args[index]) : def;
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    public double argDouble(int index, double def) {
        try {
            return index < args.length ? Double.parseDouble(args[index]) : def;
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    public Player playerArg(int index) {
        String name = arg(index);
        if (name.isEmpty()) {
            return null;
        }
        Player exact = Bukkit.getPlayerExact(name);
        return exact != null ? exact : Bukkit.getPlayer(name);
    }

    public UUID uuidArg(int index) {
        Player p = playerArg(index);
        return p != null ? p.getUniqueId() : null;
    }

    public void msg(String message) {
        sender.sendMessage(Text.color(message));
    }

    public void error(String message) {
        sender.sendMessage(Text.color("&c" + message));
    }

    public void usage(String usage) {
        sender.sendMessage(Text.color("&eUsage: " + usage));
    }
}
