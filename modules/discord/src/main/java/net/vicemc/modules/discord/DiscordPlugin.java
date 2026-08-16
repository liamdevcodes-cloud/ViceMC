package net.vicemc.modules.discord;

import net.vicemc.api.ModulePlugin;

public final class DiscordPlugin extends ModulePlugin<DiscordModule> {

    @Override
    protected DiscordModule createModule() {
        return new DiscordModule();
    }
}
