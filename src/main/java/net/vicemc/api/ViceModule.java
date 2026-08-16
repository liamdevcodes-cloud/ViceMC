package net.vicemc.api;

import java.util.List;

/**
 * A self-contained feature module loaded by ViceCore.
 * Modules are supplied as separate Paper plugins that depend on ViceCore.
 */
public interface ViceModule {

    String id();

    String displayName();

    String version();

    default List<String> dependencies() {
        return List.of();
    }

    default void onLoad(ViceModuleContext context) {
    }

    default void onEnable(ViceModuleContext context) {
    }

    default void onDisable() {
    }
}
