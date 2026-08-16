package net.vicemc.modules.heists;

import net.vicemc.api.ModulePlugin;

public final class HeistsPlugin extends ModulePlugin<HeistModule> {

    @Override
    protected HeistModule createModule() {
        return new HeistModule();
    }
}
