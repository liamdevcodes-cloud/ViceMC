package net.vicemc.modules.banking;

import net.vicemc.api.ModulePlugin;

public final class BankingPlugin extends ModulePlugin<BankingModule> {

    @Override
    protected BankingModule createModule() {
        return new BankingModule();
    }
}
