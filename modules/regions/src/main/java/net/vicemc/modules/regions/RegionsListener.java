package net.vicemc.modules.regions;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the region flags and runs the safe/danger/neutral chat indicator.
 * The indicator is a per-player repeating task on the player's own scheduler
 * so every location read happens on the correct Folia thread; the resulting
 * zone id is cached in the manager and read (data-only) by the async chat
 * handler.
 */
public final class RegionsListener implements Listener {

    private final ViceModuleContext ctx;
    private final RegionsManager manager;
    private final YamlConfig config;
    private final JavaPlugin plugin;
    private final List<String> commandAllowlist;
    private final Map<UUID, ScheduledTask> zoneTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> zonePrimed = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastWarn = new ConcurrentHashMap<>();

    public RegionsListener(ViceModuleContext ctx, RegionsManager manager,
                           YamlConfig config, JavaPlugin plugin) {
        this.ctx = ctx;
        this.manager = manager;
        this.config = config;
        this.plugin = plugin;
        this.commandAllowlist = config.getStringList("commands.allowlist");
    }

    // --- Zone indicator ---------------------------------------------------

    public void startZoneMonitor(Player player) {
        if (!config.getBoolean("indicator.enabled", true)) {
            return;
        }
        UUID id = player.getUniqueId();
        ScheduledTask existing = zoneTasks.get(id);
        if (existing != null && !existing.isCancelled()) {
            return;
        }
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, t -> tickZone(player),
                null, 20L, Math.max(5, config.getInt("indicator.interval-ticks", 20)));
        zoneTasks.put(id, task);
    }

    private void tickZone(Player player) {
        if (!player.isOnline()) {
            return;
        }
        UUID id = player.getUniqueId();
        RegionPolygon region = manager.at(player.getLocation());
        String key = region == null ? "" : region.id();
        if (zonePrimed.get(id) == null || !zonePrimed.get(id)) {
            manager.setZone(id, key);
            zonePrimed.put(id, Boolean.TRUE);
            return;
        }
        String prev = manager.zoneId(id);
        manager.setZone(id, key);
        if (key.isEmpty() || key.equals(prev) || !config.getBoolean("indicator.on-enter", true)) {
            return;
        }
        ctx.notifications().msg(player, "&8[&bRegion&8] &7You entered &f" + region.name()
                + " &7(" + manager.safety(region).label() + "&7)");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        startZoneMonitor(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        ScheduledTask task = zoneTasks.remove(id);
        if (task != null) {
            cancelQuietly(task);
        }
        zonePrimed.remove(id);
        lastWarn.remove(id);
        manager.setZone(id, "");
    }

    // --- Flag enforcement -------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }
        RegionPolygon weaponsZone = manager.at(attacker.getLocation());
        if (weaponsZone != null && !weaponsZone.flag(RegionsFlag.WEAPONS)) {
            event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof Player victim) {
            RegionPolygon pvpZone = manager.at(victim.getLocation());
            if (pvpZone != null && !pvpZone.flag(RegionsFlag.PVP)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || cause == EntityDamageEvent.DamageCause.PROJECTILE
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            return;
        }
        RegionPolygon zone = manager.at(victim.getLocation());
        if (zone != null && !zone.flag(RegionsFlag.DAMAGE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        RegionPolygon zone = manager.at(player.getLocation());
        if (zone == null || zone.flag(RegionsFlag.LOOT_DROP)) {
            return;
        }
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (bypass(player)) {
            return;
        }
        RegionPolygon zone = manager.at(player.getLocation());
        if (zone != null && !zone.flag(RegionsFlag.ITEM_DROP)) {
            event.setCancelled(true);
            warnThrottled(player, "You cannot drop items in this area.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBuild(BlockPlaceEvent event) {
        if (bypass(event.getPlayer())) {
            return;
        }
        RegionPolygon zone = manager.at(event.getBlockPlaced().getLocation());
        if (zone != null && !zone.flag(RegionsFlag.BLOCK_BUILD)) {
            event.setCancelled(true);
            warnThrottled(event.getPlayer(), "You cannot build in this area.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (bypass(event.getPlayer())) {
            return;
        }
        RegionPolygon zone = manager.at(event.getBlock().getLocation());
        if (zone != null && !zone.flag(RegionsFlag.BLOCK_BREAK)) {
            event.setCancelled(true);
            warnThrottled(event.getPlayer(), "You cannot break blocks in this area.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (bypass(event.getPlayer())) {
            return;
        }
        RegionPolygon zone = manager.at(event.getBlock().getLocation());
        if (zone != null && !zone.flag(RegionsFlag.BLOCK_BUILD)) {
            event.setCancelled(true);
            warnThrottled(event.getPlayer(), "You cannot build in this area.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (bypass(event.getPlayer())) {
            return;
        }
        RegionPolygon zone = manager.at(event.getBlock().getLocation());
        if (zone != null && !zone.flag(RegionsFlag.BLOCK_BREAK)) {
            event.setCancelled(true);
            warnThrottled(event.getPlayer(), "You cannot break blocks in this area.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        if (!(event.getEntity() instanceof Mob)) {
            return;
        }
        RegionPolygon zone = manager.at(event.getLocation());
        if (zone != null && !zone.flag(RegionsFlag.MOB_SPAWN)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (bypass(player)) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        RegionPolygon toZone = manager.at(to);
        if (toZone != null && !toZone.flag(RegionsFlag.ENTER)) {
            RegionPolygon fromZone = manager.at(from);
            if (fromZone == null || !fromZone.id().equals(toZone.id())) {
                event.setCancelled(true);
                warnThrottled(player, "You cannot enter this area.");
                return;
            }
        }
        RegionPolygon fromZone = manager.at(from);
        if (fromZone != null && !fromZone.flag(RegionsFlag.EXIT)) {
            RegionPolygon newZone = manager.at(to);
            if (newZone == null || !newZone.id().equals(fromZone.id())) {
                event.setCancelled(true);
                warnThrottled(player, "You cannot leave this area.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (bypass(player)) {
            return;
        }
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        RegionPolygon zone = manager.at(to);
        if (zone != null && !zone.flag(RegionsFlag.TELEPORT)) {
            event.setCancelled(true);
            warnThrottled(player, "You cannot teleport into this area.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (bypass(player)) {
            return;
        }
        RegionPolygon zone = manager.at(player.getLocation());
        if (zone != null && !zone.flag(RegionsFlag.FLIGHT)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player) || bypass(player)) {
            return;
        }
        RegionPolygon zone = manager.at(player.getLocation());
        if (zone != null && !zone.flag(RegionsFlag.HUNGER)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (bypass(player)) {
            return;
        }
        RegionPolygon zone = manager.zoneRegion(player.getUniqueId());
        if (zone != null && !zone.flag(RegionsFlag.CHAT)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (bypass(player)) {
            return;
        }
        RegionPolygon zone = manager.zoneRegion(player.getUniqueId());
        if (zone == null || zone.flag(RegionsFlag.COMMANDS)) {
            return;
        }
        String message = event.getMessage();
        if (message.length() > 1) {
            String base = message.substring(1);
            int space = base.indexOf(' ');
            if (space > 0) {
                base = base.substring(0, space);
            }
            if (commandAllowlist.contains(base.toLowerCase())) {
                return;
            }
        }
        event.setCancelled(true);
        warnThrottled(player, "Commands are disabled in this area.");
    }

    // --- Helpers ----------------------------------------------------------

    private boolean bypass(Player player) {
        return player.hasPermission("vicemc.regions.bypass");
    }

    private void warnThrottled(Player player, String message) {
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(player.getUniqueId());
        if (last != null && now - last < 1000L) {
            return;
        }
        lastWarn.put(player.getUniqueId(), now);
        ctx.notifications().warn(player, message);
    }

    private static void cancelQuietly(ScheduledTask task) {
        try {
            task.cancel();
        } catch (IllegalStateException ignored) {
        }
    }
}
