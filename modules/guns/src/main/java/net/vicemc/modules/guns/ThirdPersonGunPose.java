package net.vicemc.modules.guns;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.Pair;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ThirdPersonGunPose implements Listener {
    private final GunsModule module;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private ProtocolManager protocol;

    public ThirdPersonGunPose(GunsModule module) {
        this.module = module;
    }

    public void enable(Plugin plugin) {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            module.context().logger().warning("ProtocolLib is unavailable; third-person gun pose disabled.");
            return;
        }
        protocol = ProtocolLibrary.getProtocolManager();
        protocol.addPacketListener(new PacketAdapter(plugin, ListenerPriority.NORMAL,
                PacketType.Play.Server.ENTITY_EQUIPMENT) {
            @Override
            public void onPacketSending(PacketEvent event) {
                rewrite(event);
            }
        });
    }

    public void disable() {
        if (protocol != null) protocol.removePacketListeners(module.context().plugin());
        active.clear();
    }

    public void set(Player player, boolean enabled) {
        boolean changed = enabled ? active.add(player.getUniqueId()) : active.remove(player.getUniqueId());
        if (changed && protocol != null && player.isOnline()) {
            if (enabled) {
                broadcastEquipment(player, true);
            } else Bukkit.getScheduler().runTask(module.context().plugin(), () -> {
                if (player.isOnline()) broadcastEquipment(player, false);
            });
        }
    }

    public void refresh(Player player) {
        if (protocol != null && player.isOnline() && active.contains(player.getUniqueId())) {
            broadcastEquipment(player, true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(module.context().plugin(), () -> set(event.getPlayer(), isAk(event.getPlayer().getInventory().getItemInMainHand())));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        set(event.getPlayer(), false);
    }

    private boolean isAk(ItemStack item) {
        GunInstance instance = module.guns().read(item);
        if (instance == null || !instance.defOk()) return false;
        return instance.def().thirdPersonPoseEnabled();
    }

    public boolean isPoseGun(ItemStack item) { return isAk(item); }

    private void rewrite(PacketEvent event) {
        Entity entity;
        try {
            entity = event.getPacket().getEntityModifier(event).read(0);
        } catch (RuntimeException ignored) {
            return;
        }
        if (!(entity instanceof Player target) || event.getPlayer().getUniqueId().equals(target.getUniqueId())
                || !active.contains(target.getUniqueId()) || !isPoseGun(target.getInventory().getItemInMainHand())) return;

        List<Pair<EnumWrappers.ItemSlot, ItemStack>> pairs = event.getPacket().getSlotStackPairLists().read(0);
        if (pairs == null) return;
        for (int i = 0; i < pairs.size(); i++) {
            Pair<EnumWrappers.ItemSlot, ItemStack> pair = pairs.get(i);
            if (pair.getFirst() != EnumWrappers.ItemSlot.MAINHAND || !module.guns().isGun(pair.getSecond())) continue;
                pairs.set(i, new Pair<>(EnumWrappers.ItemSlot.MAINHAND, fakeCrossbow(pair.getSecond())));
        }
        event.getPacket().getSlotStackPairLists().write(0, pairs);
    }

    private void broadcastEquipment(Player target, boolean fake) {
        ItemStack item = target.getInventory().getItemInMainHand();
        if (item == null) item = new ItemStack(Material.AIR);
        if (fake && !isPoseGun(item)) return;
        ItemStack shown = fake ? fakeCrossbow(item) : item.clone();
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.ENTITY_EQUIPMENT);
        packet.getIntegers().write(0, target.getEntityId());
        packet.getSlotStackPairLists().write(0, List.of(new Pair<>(EnumWrappers.ItemSlot.MAINHAND, shown)));
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(target.getUniqueId())) continue;
            protocol.sendServerPacket(viewer, packet);
        }
    }

    private ItemStack fakeCrossbow(ItemStack item) {
        ItemStack fake = item.clone();
        ItemMeta originalMeta = fake.getItemMeta();
        fake.setType(Material.CROSSBOW);
        if (fake.getItemMeta() instanceof CrossbowMeta meta) {
            copyItemModel(item, originalMeta, meta);
            meta.setChargedProjectiles(List.of(new ItemStack(Material.ARROW)));
            fake.setItemMeta(meta);
        }
        return fake;
    }

    private void copyItemModel(ItemStack sourceItem, ItemMeta source, ItemMeta target) {
        if (source == null) return;
        try {
            Object hasModel = ItemMeta.class.getMethod("hasItemModel").invoke(source);
            if (hasModel instanceof Boolean present && present) {
                Object model = ItemMeta.class.getMethod("getItemModel").invoke(source);
                if (model != null) {
                    ItemMeta.class.getMethod("setItemModel", NamespacedKey.class).invoke(target, model);
                    return;
                }
            }
            NamespacedKey model = NamespacedKey.minecraft(sourceItem.getType().getKey().getKey());
            ItemMeta.class.getMethod("setItemModel", NamespacedKey.class).invoke(target, model);
        } catch (ReflectiveOperationException ex) {
            module.context().logger().fine("Item model component is unavailable; using custom model data fallback.");
        }
    }
}
