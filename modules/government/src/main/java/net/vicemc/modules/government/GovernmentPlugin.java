package net.vicemc.modules.government;

import net.vicemc.api.ModulePlugin;

public final class GovernmentPlugin extends ModulePlugin<GovernmentModule> {

    @Override
    protected GovernmentModule createModule() {
        return new GovernmentModule();
    }
}
