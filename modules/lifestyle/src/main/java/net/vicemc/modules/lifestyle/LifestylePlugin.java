package net.vicemc.modules.lifestyle;

import net.vicemc.api.ModulePlugin;

public final class LifestylePlugin extends ModulePlugin<LifestyleModule> {

    @Override
    protected LifestyleModule createModule() {
        return new LifestyleModule();
    }
}
