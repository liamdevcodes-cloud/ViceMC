package net.vicemc.modules.staff;

import net.vicemc.api.ModulePlugin;

public final class StaffPlugin extends ModulePlugin<StaffModule> {

    @Override
    protected StaffModule createModule() {
        return new StaffModule();
    }
}
