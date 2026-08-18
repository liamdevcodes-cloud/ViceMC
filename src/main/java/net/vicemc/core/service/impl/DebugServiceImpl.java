package net.vicemc.core.service.impl;

import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.DebugService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Registry of debug actions per system, dispatched by /admindebug.
 */
public final class DebugServiceImpl implements DebugService {

    private final Map<String, Map<String, DebugHandler>> handlers = new TreeMap<>();
    private final Map<String, Map<String, DebugTabber>> tabbers = new TreeMap<>();

    @Override
    public void register(String system, String action, DebugHandler handler) {
        handlers.computeIfAbsent(system.toLowerCase(), k -> new TreeMap<>()).put(action.toLowerCase(), handler);
    }

    @Override
    public void registerTab(String system, String action, DebugTabber tabber) {
        tabbers.computeIfAbsent(system.toLowerCase(), k -> new TreeMap<>()).put(action.toLowerCase(), tabber);
    }

    @Override
    public Set<String> systems() {
        return handlers.keySet();
    }

    @Override
    public List<String> actions(String system) {
        Map<String, DebugHandler> map = handlers.get(system.toLowerCase());
        return map == null ? List.of() : new ArrayList<>(map.keySet());
    }

    @Override
    public boolean execute(String system, String action, CommandContext c) {
        Map<String, DebugHandler> map = handlers.get(system.toLowerCase());
        if (map == null) return false;
        DebugHandler h = map.get(action.toLowerCase());
        if (h == null) return false;
        h.run(c);
        return true;
    }

    @Override
    public List<String> tab(String system, String action, CommandContext c, List<String> args) {
        Map<String, DebugTabber> map = tabbers.get(system.toLowerCase());
        if (map == null) return List.of();
        DebugTabber t = map.get(action.toLowerCase());
        return t == null ? List.of() : t.tab(c, args);
    }
}