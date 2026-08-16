package net.vicemc.core;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.BuildProtectionService;
import net.vicemc.api.service.CommandService;
import net.vicemc.api.service.EconomyService;
import net.vicemc.api.service.EventBus;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.service.NotificationService;
import net.vicemc.api.service.RegionService;
import net.vicemc.api.service.StorageService;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Logger;

public final class ViceModuleContextImpl implements ViceModuleContext {

    private final JavaPlugin plugin;
    private final ViceCore core;

    public ViceModuleContextImpl(JavaPlugin plugin, ViceCore core) {
        this.plugin = plugin;
        this.core = core;
    }

    @Override
    public JavaPlugin plugin() {
        return plugin;
    }

    @Override
    public File dataFolder() {
        return plugin.getDataFolder();
    }

    @Override
    public Logger logger() {
        return plugin.getLogger();
    }

    @Override
    public EconomyService economy() {
        return core.economy();
    }

    @Override
    public StorageService storage() {
        return core.storage();
    }

    @Override
    public CommandService commands() {
        return core.commands();
    }

    @Override
    public GUIService gui() {
        return core.gui();
    }

    @Override
    public RegionService regions() {
        return core.regions();
    }

    @Override
    public NotificationService notifications() {
        return core.notifications();
    }

    @Override
    public EventBus events() {
        return core.events();
    }

    @Override
    public BuildProtectionService buildProtection() {
        return core.buildProtection();
    }

    @Override
    public YamlConfig yaml(String fileName) {
        return new YamlConfig(new File(plugin.getDataFolder(), fileName), plugin.getResource(fileName));
    }
}
