package net.vicemc.modules.guns;

import net.vicemc.api.ModulePlugin;

public final class GunsPlugin extends ModulePlugin<GunsModule> {

    @Override
    protected GunsModule createModule() {
        return new GunsModule();
    }
}
