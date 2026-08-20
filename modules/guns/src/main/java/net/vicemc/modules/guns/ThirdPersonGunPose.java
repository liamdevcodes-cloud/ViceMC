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
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ThirdPersonGunPose implements Listener {
    private final GunsModule module;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Integer, PoseSnapshot> presentations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> entityIds = new ConcurrentHashMap<>();
    private ProtocolManager protocol;

    private record PoseSnapshot(UUID playerId, ItemStack shown) {
    }

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
        presentations.clear();
        entityIds.clear();
    }

    public void set(Player player, boolean enabled) {
        boolean changed = enabled ? active.add(player.getUniqueId()) : active.remove(player.getUniqueId());
        if (protocol == null || !player.isOnline()) return;
        if (enabled) {
            cachePresentation(player);
        } else if (changed) {
            Integer entityId = entityIds.remove(player.getUniqueId());
            if (entityId != null) presentations.remove(entityId);
        }
    }

    public void refresh(Player player) {
        if (protocol != null && player.isOnline() && active.contains(player.getUniqueId())) {
            cachePresentation(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().run(module.context().plugin(), task -> {
            if (player.isOnline()) set(player, isAk(player.getInventory().getItemInMainHand()));
        }, null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        set(event.getPlayer(), false);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(module.context().plugin(), task -> {
            if (player.isOnline()) set(player, isAk(player.getInventory().getItemInMainHand()));
        }, null, 1L);
    }

    private boolean isAk(ItemStack item) {
        GunInstance instance = module.guns().read(item);
        if (instance == null || !instance.defOk()) return false;
        return instance.def().thirdPersonPoseEnabled();
    }

    public boolean isPoseGun(ItemStack item) { return isAk(item); }

    private void rewrite(PacketEvent event) {
        Integer entityId = event.getPacket().getIntegers().readSafely(0);
        if (entityId == null) return;
        PoseSnapshot presentation = presentations.get(entityId);
        if (presentation == null || event.getPlayer().getUniqueId().equals(presentation.playerId())) return;

        List<Pair<EnumWrappers.ItemSlot, ItemStack>> pairs = event.getPacket().getSlotStackPairLists().read(0);
        if (pairs == null) return;
        for (int i = 0; i < pairs.size(); i++) {
            Pair<EnumWrappers.ItemSlot, ItemStack> pair = pairs.get(i);
            if (pair.getFirst() != EnumWrappers.ItemSlot.MAINHAND || !module.guns().isGun(pair.getSecond())) continue;
            pairs.set(i, new Pair<>(EnumWrappers.ItemSlot.MAINHAND, presentation.shown().clone()));
        }
        event.getPacket().getSlotStackPairLists().write(0, pairs);
    }

    private void cachePresentation(Player target) {
        ItemStack item = target.getInventory().getItemInMainHand();
        if (!isPoseGun(item)) {
            set(target, false);
            return;
        }
        int entityId = target.getEntityId();
        entityIds.put(target.getUniqueId(), entityId);
        presentations.put(entityId, new PoseSnapshot(target.getUniqueId(), fakeCrossbow(item)));
    }

    private ItemStack fakeCrossbow(ItemStack item) {
        ItemStack fake = item.clone();
        ItemMeta originalMeta = fake.getItemMeta();
        fake.setType(Material.CROSSBOW);
        if (fake.getItemMeta() instanceof CrossbowMeta meta) {
            // Preserve custom_model_data so ItemsAdder crossbow overrides match
            if (originalMeta != null && originalMeta.hasCustomModelData()) {
                meta.setCustomModelData(originalMeta.getCustomModelData());
            }
            // Copy the item_model component (1.21.4+) only if the source actually has one
            copyItemModel(originalMeta, meta);
            meta.setChargedProjectiles(List.of(new ItemStack(Material.ARROW)));
            fake.setItemMeta(meta);
        }
        return fake;
    }

    private void copyItemModel(ItemMeta source, ItemMeta target) {
        if (source == null) return;
        try {
            Object hasModel = ItemMeta.class.getMethod("hasItemModel").invoke(source);
            if (hasModel instanceof Boolean present && present) {
                Object model = ItemMeta.class.getMethod("getItemModel").invoke(source);
                if (model != null) {
                    ItemMeta.class.getMethod("setItemModel", NamespacedKey.class).invoke(target, model);
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // item_model component not available on this server version — CMD is enough
        }
    }
}
