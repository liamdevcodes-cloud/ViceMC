package net.vicemc.modules.properties;

import net.vicemc.api.ModulePlugin;

public final class PropertiesPlugin extends ModulePlugin<PropertiesModule> {

    @Override
    protected PropertiesModule createModule() {
        return new PropertiesModule();
    }
}
