package net.vicemc.modules.serial;

import net.vicemc.api.ModulePlugin;

public final class SerialPlugin extends ModulePlugin<SerialModule> {

    @Override
    protected SerialModule createModule() {
        return new SerialModule();
    }
}
