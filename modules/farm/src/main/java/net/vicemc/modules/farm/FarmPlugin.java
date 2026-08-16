package net.vicemc.modules.farm;

import net.vicemc.api.ModulePlugin;

public final class FarmPlugin extends ModulePlugin<FarmModule> {

    @Override
    protected FarmModule createModule() {
        return new FarmModule();
    }
}
