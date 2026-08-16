package net.vicemc.core.service.impl;

import net.kyori.adventure.text.Component;
import net.vicemc.api.service.GUIService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Inventory GUI framework backed by a single click listener.
 */
public final class GUIServiceImpl implements GUIService, Listener {

    private final JavaPlugin plugin;
    private final Map<UUID, GuiHolder> open = new HashMap<>();

    public GUIServiceImpl(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public GuiBuilder builder(Component title, int rows) {
        return new GuiBuilderImpl(title, rows);
    }

    @Override
    public void closeAll(Player player) {
        player.closeInventory();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        open.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        GuiHolder holder = open.get(player.getUniqueId());
        if (holder == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() instanceof PlayerInventory) {
            return;
        }
        BiConsumer<Player, ClickType> action = holder.items.get(event.getSlot());
        if (action != null) {
            action.accept(player, event.getClick());
        }
    }

    private static final class GuiHolder {
        final Inventory inventory;
        final Map<Integer, BiConsumer<Player, ClickType>> items = new HashMap<>();

        GuiHolder(Component title, int rows) {
            this.inventory = Bukkit.createInventory(null, rows * 9, title);
        }
    }

    private final class GuiBuilderImpl implements GuiBuilder {
        private final GuiHolder holder;

        GuiBuilderImpl(Component title, int rows) {
            this.holder = new GuiHolder(title, Math.max(1, Math.min(6, rows)));
        }

        @Override
        public GuiBuilder item(int slot, ItemStack item, BiConsumer<Player, ClickType> click) {
            holder.inventory.setItem(slot, item);
            if (click != null) {
                holder.items.put(slot, click);
            }
            return this;
        }

        @Override
        public GuiBuilder onClick(int slot, BiConsumer<Player, ClickType> click) {
            if (click != null) {
                holder.items.put(slot, click);
            }
            return this;
        }

        @Override
        public GuiBuilder fill(ItemStack filler) {
            for (int i = 0; i < holder.inventory.getSize(); i++) {
                if (holder.inventory.getItem(i) == null) {
                    holder.inventory.setItem(i, filler);
                }
            }
            return this;
        }

        @Override
        public GuiBuilder open(Player player) {
            player.openInventory(holder.inventory);
            open.put(player.getUniqueId(), holder);
            return this;
        }
    }
}
