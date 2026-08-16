package net.vicemc.api;

import net.vicemc.core.ViceCore;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Base class for module plugins. Every module ships as its own Paper plugin,
 * depends on ViceCore, and registers itself through this base class.
 *
 * @param <M> the module type this plugin provides
 */
public abstract class ModulePlugin<M extends ViceModule> extends JavaPlugin {

    private M module;

    @Override
    public final void onEnable() {
        ViceCore core = ViceCore.get();
        if (core == null) {
            getLogger().severe("ViceCore is required. Disabling " + getName() + ".");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.module = createModule();
        core.getModuleRegistry().register(this, module);
        getLogger().info("Registered module '" + module.displayName() + "'.");
    }

    @Override
    public final void onDisable() {
        if (module != null) {
            ViceCore core = ViceCore.get();
            if (core != null) {
                core.getModuleRegistry().unregister(module);
            }
        }
    }

    public final M module() {
        return module;
    }

    protected abstract M createModule();
}
