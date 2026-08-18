package net.vicemc.api;

import net.vicemc.api.service.BuildProtectionService;
import net.vicemc.api.service.CommandService;
import net.vicemc.api.service.DebugService;
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

/**
 * Access point for all core services granted to a {@link ViceModule}.
 */
public interface ViceModuleContext {

    JavaPlugin plugin();

    File dataFolder();

    Logger logger();

    EconomyService economy();

    StorageService storage();

    CommandService commands();

    GUIService gui();

    RegionService regions();

    NotificationService notifications();

    EventBus events();

    /**
     * Admin debug system. Modules register fake actions so admins can simulate
     * events (win/lose/capture/etc.) without waiting for real conditions.
     */
    DebugService debug();

    /**
     * Map-wide build protection and the block claims modules register into it.
     */
    BuildProtectionService buildProtection();

    /**
     * Loads a YAML config from the module data folder, seeding defaults from
     * the module's resources if the file does not exist yet.
     */
    YamlConfig yaml(String fileName);
}
