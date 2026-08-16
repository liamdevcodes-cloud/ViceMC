package net.vicemc.modules.regions;

import net.vicemc.api.ModulePlugin;

public final class RegionsPlugin extends ModulePlugin<RegionsModule> {

    @Override
    protected RegionsModule createModule() {
        return new RegionsModule();
    }
}
