package net.vicemc.modules.transport;

import net.vicemc.api.ModulePlugin;

public final class TeleportPlugin extends ModulePlugin<TeleportModule> {

    @Override
    protected TeleportModule createModule() {
        return new TeleportModule();
    }
}
