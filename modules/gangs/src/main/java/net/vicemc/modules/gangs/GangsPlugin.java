package net.vicemc.modules.gangs;

import net.vicemc.api.ModulePlugin;

public final class GangsPlugin extends ModulePlugin<GangModule> {

    @Override
    protected GangModule createModule() {
        return new GangModule();
    }
}
