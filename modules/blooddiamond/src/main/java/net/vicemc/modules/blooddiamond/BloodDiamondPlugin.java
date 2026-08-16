package net.vicemc.modules.blooddiamond;

import net.vicemc.api.ModulePlugin;

public final class BloodDiamondPlugin extends ModulePlugin<BloodDiamondModule> {

    @Override
    protected BloodDiamondModule createModule() {
        return new BloodDiamondModule();
    }
}
