package net.vicemc.modules.cosmetics;

import net.vicemc.api.ModulePlugin;

public final class CosmeticsPlugin extends ModulePlugin<CosmeticsModule> {

    @Override
    protected CosmeticsModule createModule() {
        return new CosmeticsModule();
    }
}
