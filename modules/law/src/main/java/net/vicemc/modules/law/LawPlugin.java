package net.vicemc.modules.law;

import net.vicemc.api.ModulePlugin;

public final class LawPlugin extends ModulePlugin<LawModule> {

    @Override
    protected LawModule createModule() {
        return new LawModule();
    }
}
