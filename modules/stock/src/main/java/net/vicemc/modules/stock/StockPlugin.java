package net.vicemc.modules.stock;

import net.vicemc.api.ModulePlugin;

public final class StockPlugin extends ModulePlugin<StockModule> {

    @Override
    protected StockModule createModule() {
        return new StockModule();
    }
}
