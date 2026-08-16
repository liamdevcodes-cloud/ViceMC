package net.vicemc.core;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Tracks registered modules and drives their lifecycle. Modules register
 * during their own plugin onEnable; dependency resolution is implicit because
 * every module plugin declares its dependencies in plugin.yml.
 */
public final class ModuleRegistry {

    private final Map<String, Entry> modules = new LinkedHashMap<>();

    public void register(JavaPlugin plugin, ViceModule module) {
        if (modules.containsKey(module.id())) {
            plugin.getLogger().warning("Module '" + module.id() + "' is already registered.");
            return;
        }
        modules.put(module.id(), new Entry(plugin, module));
        ViceModuleContext context = new ViceModuleContextImpl(plugin, ViceCore.get());
        try {
            module.onLoad(context);
            module.onEnable(context);
        } catch (Throwable t) {
            modules.remove(module.id());
            plugin.getLogger().severe("Failed to enable module '" + module.id() + "': " + t.getMessage());
            t.printStackTrace();
        }
    }

    public void unregister(ViceModule module) {
        Entry entry = modules.get(module.id());
        if (entry == null) {
            return;
        }
        try {
            module.onDisable();
        } catch (Throwable t) {
            entry.plugin().getLogger().severe("Failed to disable module '" + module.id() + "': " + t.getMessage());
            t.printStackTrace();
        }
        modules.remove(module.id());
    }

    public void shutdownAll() {
        for (Entry entry : new ArrayList<>(modules.values())) {
            try {
                entry.module().onDisable();
            } catch (Throwable t) {
                entry.plugin().getLogger().severe("Failed to disable module '" + entry.module().id() + "': " + t.getMessage());
                t.printStackTrace();
            }
        }
        modules.clear();
    }

    public Optional<ViceModule> module(String id) {
        Entry entry = modules.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.module());
    }

    public Collection<ViceModule> modules() {
        return modules.values().stream().map(Entry::module).toList();
    }

    private record Entry(JavaPlugin plugin, ViceModule module) {
    }
}
