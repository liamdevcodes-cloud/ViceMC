package net.vicemc.modules.regions;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The admin region wand: left-click blocks to add polygon vertices, right-click
 * a block to close the selection, sneak + left-click air to clear it. The
 * finished selection is saved with /region create.
 */
public final class RegionsWandListener implements Listener {

    private static final NamespacedKey WAND_KEY = NamespacedKey.fromString("vicemc:regions_wand");

    private final ViceModuleContext ctx;
    private final RegionsManager manager;
    private final YamlConfig config;
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    public RegionsWandListener(ViceModuleContext ctx, RegionsManager manager, YamlConfig config) {
        this.ctx = ctx;
        this.manager = manager;
        this.config = config;
    }

    public static boolean isWand(ItemStack item) {
        return item != null && ItemBuilder.hasTag(item, WAND_KEY, "true");
    }

    public static NamespacedKey wandKey() {
        return WAND_KEY;
    }

    public ItemStack wand() {
        Material material = Material.matchMaterial(config.getString("wand.material", "GOLDEN_AXE"));
        return ItemBuilder.of(material == null ? Material.GOLDEN_AXE : material)
                .name(config.getString("wand.name", "&b&lRegion Wand"))
                .modelData(config.getInt("wand.model-data", 520))
                .lore(config.getStringList("wand.lore").toArray(new String[0]))
                .tag(WAND_KEY, "true")
                .build();
    }

    public void giveWand(Player player) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(wand());
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
        }
        ctx.notifications().msg(player, "&aHere is your region wand. Left-click blocks to add "
                + "vertices, right-click to close the polygon.");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack hand = event.getItem();
        if (!isWand(hand)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("vicemc.regions.admin")) {
            ctx.notifications().warn(player, "You do not have permission to use the region wand.");
            return;
        }
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR && player.isSneaking()) {
            manager.clearSelection(player.getUniqueId());
            ctx.notifications().msg(player, "&7Region selection cleared.");
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        if (!clickCooldown(player)) {
            return;
        }
        if (action == Action.LEFT_CLICK_BLOCK) {
            addVertex(player, clicked);
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            closeSelection(player);
        }
    }

    private void addVertex(Player player, Block clicked) {
        UUID id = player.getUniqueId();
        List<Location> selection = manager.selection(id);
        if (!selection.isEmpty() && !selection.get(0).getWorld().equals(clicked.getWorld())) {
            ctx.notifications().warn(player, "All vertices must be in the same world.");
            return;
        }
        Location loc = clicked.getLocation();
        selection.add(loc);
        ctx.notifications().msg(player, "&bRegion wand &7- vertex &f" + selection.size()
                + "&7: &f" + loc.getBlockX() + ", " + loc.getBlockZ()
                + "&7. Left-click more points, right-click to close.");
    }

    private void closeSelection(Player player) {
        List<Location> selection = manager.selection(player.getUniqueId());
        int count = selection.size();
        if (count < 3) {
            ctx.notifications().warn(player, "You need at least 3 vertices to close a polygon "
                    + "(currently " + count + ").");
            return;
        }
        ctx.notifications().msg(player, "&aPolygon closed with &f" + count
                + "&a vertices. Run &e/region create <id> [name]&a to save it.");
    }

    private boolean clickCooldown(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < 200L) {
            return false;
        }
        lastClick.put(player.getUniqueId(), now);
        return true;
    }
}
