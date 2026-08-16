package net.vicemc.api.service;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.function.BiConsumer;

/**
 * Fluent inventory GUI framework with click callbacks.
 */
public interface GUIService {

    GuiBuilder builder(Component title, int rows);

    void closeAll(Player player);

    interface GuiBuilder {

        GuiBuilder item(int slot, ItemStack item, BiConsumer<Player, ClickType> click);

        GuiBuilder onClick(int slot, BiConsumer<Player, ClickType> click);

        GuiBuilder fill(ItemStack filler);

        GuiBuilder open(Player player);
    }
}
