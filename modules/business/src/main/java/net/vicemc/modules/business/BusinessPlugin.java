package net.vicemc.modules.business;

import net.vicemc.api.ModulePlugin;

public final class BusinessPlugin extends ModulePlugin<BusinessModule> {

    @Override
    protected BusinessModule createModule() {
        return new BusinessModule();
    }
}
