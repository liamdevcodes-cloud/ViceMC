package net.vicemc.modules.phone;

import net.vicemc.api.ModulePlugin;

public final class PhonePlugin extends ModulePlugin<PhoneModule> {

    @Override
    protected PhoneModule createModule() {
        return new PhoneModule();
    }
}
