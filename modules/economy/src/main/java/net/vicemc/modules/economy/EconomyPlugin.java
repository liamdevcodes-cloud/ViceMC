package net.vicemc.modules.economy;

import net.vicemc.api.ModulePlugin;

public final class EconomyPlugin extends ModulePlugin<EconomyModule> {

    @Override
    protected EconomyModule createModule() {
        return new EconomyModule();
    }
}
