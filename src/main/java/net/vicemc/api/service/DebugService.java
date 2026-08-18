package net.vicemc.api.service;

import java.util.List;
import java.util.Set;

/**
 * Admin debug system. Modules register fake actions so admins can simulate
 * events (win/lose/capture/etc.) without waiting for real conditions.
 * Usage: /admindebug <system> <action> [args...]
 */
public interface DebugService {

    void register(String system, String action, DebugHandler handler);

    void registerTab(String system, String action, DebugTabber tabber);

    Set<String> systems();

    List<String> actions(String system);

    boolean execute(String system, String action, CommandContext c);

    List<String> tab(String system, String action, CommandContext c, List<String> args);

    @FunctionalInterface
    interface DebugHandler {
        void run(CommandContext c);
    }

    @FunctionalInterface
    interface DebugTabber {
        List<String> tab(CommandContext c, List<String> args);
    }
}