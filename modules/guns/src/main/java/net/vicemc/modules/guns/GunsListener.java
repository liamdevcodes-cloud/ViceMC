package net.vicemc.modules.guns;

import net.vicemc.api.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Firing, aiming, reloading and bullet impacts. Guns are identified by their
 * persistent tags; each shot spawns a gravity-less snowball projectile that
 * carries the damage and the shooter, and applies real damage on impact.
 *
 * Controls:
 *  - Right-click: shoot (auto-reloads when the magazine is empty)
 *  - Shift + right-click: toggle aim (tighter spread and a scoped FOV zoom)
 *  - Q: reload
 */
public final class GunsListener implements Listener {

    public static final NamespacedKey PROJ_GUN_KEY = NamespacedKey.fromString("vicemc:gun-shot");
    public static final NamespacedKey PROJ_DAMAGE_KEY = NamespacedKey.fromString("vicemc:gun-damage");
    public static final NamespacedKey PROJ_SHOOTER_KEY = NamespacedKey.fromString("vicemc:gun-shooter");

    private final GunsModule module;
    private final JavaPlugin plugin;

    private final Map<UUID, Long> nextFire = new ConcurrentHashMap<>();
    private final Set<UUID> aiming = ConcurrentHashMap.newKeySet();
    private final Map<UUID, ScheduledTask> aimTasks = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> reloadTasks = new ConcurrentHashMap<>();
    private final Map<UUID, PendingReload> reloads = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastNeedAmmoMessage = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastReloadMessage = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveProjectile> activeProjectiles = new ConcurrentHashMap<>();

    public GunsListener(GunsModule module) {
        this.module = module;
        this.plugin = module.context().plugin();
    }

    private record PendingReload(GunDefinition def, String serial) {
    }

    private static final class ActiveProjectile {
        private final UUID shooterId;
        private final Projectile hitbox;
        private final ItemDisplay display;
        private final double maxDistance;
        private Location lastLocation;
        private double distanceTravelled;
        private ScheduledTask timeoutTask;
        private ScheduledTask followTask;

        private ActiveProjectile(UUID shooterId, Projectile hitbox, ItemDisplay display, double maxDistance) {
            this.shooterId = shooterId;
            this.hitbox = hitbox;
            this.display = display;
            this.maxDistance = maxDistance;
            this.lastLocation = hitbox.getLocation();
        }
    }

    public void shutdown() {
        reloadTasks.values().forEach(task -> cancelQuietly(task));
        reloadTasks.clear();
        reloads.clear();
        lastNeedAmmoMessage.clear();
        lastReloadMessage.clear();
        nextFire.clear();
        aiming.clear();
        aimTasks.values().forEach(task -> cancelQuietly(task));
        aimTasks.clear();
        activeProjectiles.keySet().forEach(this::cleanupProjectile);
    }

    // --- Shooting ---------------------------------------------------------

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !module.guns().isGun(item)) {
            return;
        }
        Player player = event.getPlayer();
        event.setCancelled(true);
        if (player.isSneaking()) {
            toggleAim(player);
            return;
        }
        shoot(player, item);
    }

    private void shoot(Player player, ItemStack item) {
        GunInstance instance = module.guns().read(item);
        if (instance == null || !instance.defOk()) {
            module.context().notifications().action(player, "&cThis gun is no longer in the arsenal.");
            return;
        }
        GunDefinition def = instance.def();
        if (instance.broken()) {
            module.playDry(player);
            module.context().notifications().action(player, "&cThis gun is broken.");
            return;
        }
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long next = nextFire.get(uuid);
        if (next != null && now < next) {
            return;
        }
        if (reloadTasks.containsKey(uuid)) {
            module.context().notifications().action(player, "&6Reloading...");
            return;
        }
        if (instance.ammoInMag() <= 0) {
            startReload(player, instance);
            return;
        }

        boolean accurate = aiming.contains(uuid);
        double spread = def.spread * (accurate ? 0.35 : 1.0);
        int pellets = Math.max(1, def.pellets);
        for (int i = 0; i < pellets; i++) {
            fireProjectile(player, def, spread);
        }

        int ammo = instance.ammoInMag() - 1;
        int durability = instance.durability() - 1;
        module.guns().updateGun(item, def, instance.serial(), durability, ammo);
        module.thirdPersonGunPose().refresh(player);
        nextFire.put(uuid, now + (long) (1000.0 / Math.max(0.5, def.fireRate)));
        module.playShot(player.getEyeLocation(), def);

        if (durability <= 0) {
            module.playDry(player);
            module.context().notifications().msg(player, "&cYour " + def.name
                    + " (serial " + instance.serial() + ") broke.");
        }
    }

    private void fireProjectile(Player player, GunDefinition def, double spread) {
        Location eye = player.getEyeLocation();
        double yaw = eye.getYaw() + rand(-spread, spread);
        double pitch = eye.getPitch() + rand(-spread, spread);
        Vector dir = direction(yaw, pitch);

        double velocity = Math.max(0.1, def.bulletVelocity);
        Projectile hitbox;
        if (def.arrowProjectileEnabled()) {
            Arrow arrow = player.launchProjectile(Arrow.class, dir.multiply(velocity));
            arrow.setGravity(true);
            arrow.setPickupStatus(Arrow.PickupStatus.DISALLOWED);
            arrow.setCritical(false);
            arrow.setDamage(0.0);
            arrow.setKnockbackStrength(0);
            arrow.setPersistent(false);
            hitbox = arrow;
        } else {
            Snowball ball = player.launchProjectile(Snowball.class, dir.multiply(velocity));
            ball.setGravity(false);
            ball.setItem(new ItemStack(Material.AIR));
            hitbox = ball;
        }
        hitbox.setVisibleByDefault(false);
        hitbox.setSilent(true);
        hitbox.setShooter(player);
        ItemDisplay display = spawnProjectileDisplay(hitbox, def);
        debugProjectile("spawn", hitbox, display, dir, velocity, def);
        PersistentDataContainer pdc = hitbox.getPersistentDataContainer();
        pdc.set(PROJ_GUN_KEY, PersistentDataType.STRING, "true");
        pdc.set(PROJ_DAMAGE_KEY, PersistentDataType.STRING, String.valueOf(def.damage));
        pdc.set(PROJ_SHOOTER_KEY, PersistentDataType.STRING, player.getUniqueId().toString());

        ActiveProjectile active = new ActiveProjectile(player.getUniqueId(), hitbox, display, def.range);
        activeProjectiles.put(hitbox.getUniqueId(), active);
        active.timeoutTask = hitbox.getScheduler().runDelayed(plugin,
                task -> cleanupProjectile(hitbox.getUniqueId()), null, 20L * 30L);
        if (display != null) {
            active.followTask = display.getScheduler().runAtFixedRate(plugin, task -> {
                if (hitbox.isDead() || !hitbox.isValid() || display.isDead() || !display.isValid()) {
                    cleanupProjectile(hitbox.getUniqueId());
                    return;
                }
                Location current = displayLocation(hitbox.getLocation());
                active.distanceTravelled += active.lastLocation.distance(current);
                active.lastLocation = current;
                if (active.distanceTravelled >= active.maxDistance) {
                    cleanupProjectile(hitbox.getUniqueId());
                    return;
                }
                applyProjectileTransform(display, hitbox.getVelocity(), def.effectiveProjectileScale());
                display.teleport(current);
            }, null, 1L, 1L);
        }

        eye.getWorld().spawnParticle(Particle.CRIT, eye.clone().add(dir.clone().multiply(0.8)),
                4, 0.06, 0.06, 0.06, 0.02);
    }

    // --- Impacts ----------------------------------------------------------

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        PersistentDataContainer pdc = projectile.getPersistentDataContainer();
        if (!"true".equals(pdc.get(PROJ_GUN_KEY, PersistentDataType.STRING))) {
            return;
        }
        event.setCancelled(true);
        double damage = parseDouble(pdc.get(PROJ_DAMAGE_KEY, PersistentDataType.STRING), 0.0);
        Player shooter = parseShooter(pdc.get(PROJ_SHOOTER_KEY, PersistentDataType.STRING));

        Entity hit = event.getHitEntity();
        if (hit != null) {
            if (shooter != null && hit.equals(shooter)) {
                cleanupProjectile(projectile.getUniqueId());
                return;
            }
            if (hit instanceof Damageable damageable) {
                if (shooter != null && !shooter.isDead()) {
                    damageable.damage(damage, shooter);
                } else {
                    damageable.damage(damage);
                }
            }
            module.playHit(projectile.getLocation());
            if (hit instanceof Player victim) {
                victim.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, victim.getEyeLocation(),
                        6, 0.3, 0.3, 0.3, 0.02);
                Vector knock = projectile.getVelocity().normalize().multiply(0.3).setY(0.12);
                victim.setVelocity(victim.getVelocity().add(knock));
            } else {
                projectile.getWorld().spawnParticle(Particle.FLAME, projectile.getLocation(), 5, 0.05, 0.05, 0.05, 0.01);
            }
        } else if (event.getHitBlock() != null) {
            projectile.getWorld().spawnParticle(Particle.FLAME, projectile.getLocation(), 5, 0.05, 0.05, 0.05, 0.01);
            projectile.getWorld().playSound(projectile.getLocation(), Sound.BLOCK_STONE_HIT, 0.4f, 0.6f);
        }
        cleanupProjectile(projectile.getUniqueId());
    }

    @EventHandler
    public void onProjectileDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Projectile projectile)) return;
        if ("true".equals(projectile.getPersistentDataContainer()
                .get(PROJ_GUN_KEY, PersistentDataType.STRING))) {
            event.setCancelled(true);
        }
    }

    private ItemDisplay spawnProjectileDisplay(Projectile hitbox, GunDefinition def) {
        Material material;
        if (def.projectileMaterial == null || def.projectileMaterial.isBlank()) {
            material = def.arrowProjectileEnabled() ? Material.ARROW : Material.SNOWBALL;
        } else {
            try {
                material = Material.valueOf(def.projectileMaterial);
            } catch (IllegalArgumentException ex) {
                material = def.arrowProjectileEnabled() ? Material.ARROW : Material.SNOWBALL;
            }
        }
        ItemStack item = new ItemStack(material);
        if (def.projectileModelData > 0) {
            item.editMeta(meta -> meta.setCustomModelData(def.projectileModelData));
        }
        ItemModelSupport.apply(item, def.projectileItemModel);
        ItemDisplay display = hitbox.getWorld().spawn(displayLocation(hitbox.getLocation()), ItemDisplay.class);
        display.setItemStack(item);
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        applyProjectileTransform(display, hitbox.getVelocity(), def.effectiveProjectileScale());
        display.setBillboard(Display.Billboard.FIXED);
        display.setInterpolationDuration(1);
        display.setTeleportDuration(1);
        display.setViewRange(64.0f);
        display.setPersistent(false);
        return display;
    }

    private Location displayLocation(Location hitboxLocation) {
        Location location = hitboxLocation.clone();
        location.setYaw(0.0f);
        location.setPitch(0.0f);
        return location;
    }

    private void applyProjectileTransform(ItemDisplay display, Vector velocity, double configuredScale) {
        if (velocity.lengthSquared() <= 0.0001) return;
        Vector3f direction = new Vector3f((float) velocity.getX(), (float) velocity.getY(), (float) velocity.getZ())
                .normalize();
        Vector3f up = Math.abs(direction.y) > 0.999f
                ? new Vector3f(1.0f, 0.0f, 0.0f) : new Vector3f(0.0f, 1.0f, 0.0f);
        Quaternionf rotation = new Quaternionf().lookAlong(direction, up).invert();
        float scale = (float) Math.max(0.1, Math.min(5.0, configuredScale));
        display.setTransformation(new Transformation(
                new Vector3f(), rotation, new Vector3f(scale), new Quaternionf()));
    }

    private void debugProjectile(String phase, Projectile hitbox, ItemDisplay display,
                                 Vector initialDirection, double velocity, GunDefinition def) {
        if (!module.config().getBoolean("debug-projectiles", true)) return;
        Location hitboxLocation = hitbox.getLocation();
        String displayState = display == null ? "none" : "yaw=" + display.getLocation().getYaw()
                + ",pitch=" + display.getLocation().getPitch();
        module.context().logger().info("[ProjectileDebug] " + phase
                + " gun=" + def.id
                + " mode=" + def.projectileMode
                + " velocity=" + velocity
                + " dir=" + vectorText(initialDirection)
                + " hitbox=" + vectorText(hitbox.getVelocity())
                + " hitboxRot=" + hitboxLocation.getYaw() + "/" + hitboxLocation.getPitch()
                + " display=" + displayState
                + " material=" + def.projectileMaterial
                + " modelData=" + def.projectileModelData);
    }

    private String vectorText(Vector vector) {
        return String.format(java.util.Locale.ROOT, "(%.3f,%.3f,%.3f)",
                vector.getX(), vector.getY(), vector.getZ());
    }

    private void cleanupProjectile(UUID projectileId) {
        ActiveProjectile active = activeProjectiles.remove(projectileId);
        if (active == null) return;
        cancelQuietly(active.timeoutTask);
        cancelQuietly(active.followTask);
        if (!active.hitbox.isDead()) active.hitbox.remove();
        if (active.display != null && !active.display.isDead()) active.display.remove();
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        UUID worldId = event.getWorld().getUID();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        activeProjectiles.entrySet().stream()
                .filter(entry -> {
                    Location location = entry.getValue().hitbox.getLocation();
                    return location.getWorld() != null
                            && location.getWorld().getUID().equals(worldId)
                            && location.getBlockX() >> 4 == chunkX
                            && location.getBlockZ() >> 4 == chunkZ;
                })
                .map(Map.Entry::getKey)
                .toList()
                .forEach(this::cleanupProjectile);
    }

    private void cleanupProjectilesByShooter(UUID shooterId) {
        activeProjectiles.entrySet().stream()
                .filter(entry -> entry.getValue().shooterId.equals(shooterId))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(this::cleanupProjectile);
    }

    // --- Aiming -----------------------------------------------------------

    private void toggleAim(Player player) {
        UUID uuid = player.getUniqueId();
        if (aiming.remove(uuid)) {
            stopAimZoom(player);
            module.playAim(player);
            module.context().notifications().action(player, "&6Hip fire");
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        GunInstance instance = module.guns().read(held);
        if (instance == null || !instance.defOk()) {
            return;
        }
        aiming.add(uuid);
        if (instance.def().thirdPersonPoseEnabled()) {
            module.thirdPersonGunPose().set(player, true);
        }
        startAimZoom(player, instance.def().gunType());
        module.playAim(player);
        module.context().notifications().action(player, "&6Aiming &8- shift + right-click to release");
    }

    private void startAimZoom(Player player, GunType type) {
        FovZoom.zoom(player, module.aimZoom(type));
        UUID uuid = player.getUniqueId();
        aimTasks.computeIfAbsent(uuid, id -> player.getScheduler().runAtFixedRate(plugin, task -> {
            if (player.isOnline() && aiming.contains(id)) {
                FovZoom.zoom(player, module.aimZoom(type));
                return;
            }
            ScheduledTask scheduled = aimTasks.remove(id);
            if (scheduled != null) {
                cancelQuietly(scheduled);
            }
        }, null, 10L, 10L));
    }

    private void stopAimZoom(Player player) {
        UUID uuid = player.getUniqueId();
        ScheduledTask task = aimTasks.remove(uuid);
        if (task != null) {
            cancelQuietly(task);
        }
        FovZoom.reset(player);
    }

    // --- Reloading --------------------------------------------------------

    private void startReload(Player player, GunInstance instance) {
        GunDefinition def = instance.def();
        if (def == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (reloadTasks.containsKey(uuid)) {
            return;
        }
        if (instance.broken()) {
            module.playDry(player);
            module.context().notifications().action(player, "&cThis gun is broken.");
            return;
        }
        int need = def.magSize - instance.ammoInMag();
        if (need <= 0) {
            module.context().notifications().action(player, "&7The magazine is full.");
            return;
        }
        int have = module.guns().countAmmo(player, def.gunType().ammo());
        if (have < need) {
            module.playDry(player);
            long now = System.currentTimeMillis();
            Long last = lastNeedAmmoMessage.get(uuid);
            if (last == null || now - last >= 3000L) {
                lastNeedAmmoMessage.put(uuid, now);
                module.context().notifications().action(player, "&cNeed &f" + need
                        + "&c x " + def.gunType().ammo().display() + " - you have &f" + have + "&c.");
            }
            return;
        }

        reloads.put(uuid, new PendingReload(def, instance.serial()));
        module.playReload(player, def);
        long now = System.currentTimeMillis();
        Long lastReload = lastReloadMessage.get(uuid);
        if (lastReload == null || now - lastReload >= 3000L) {
            lastReloadMessage.put(uuid, now);
            module.context().notifications().action(player, "&6Reloading...");
        }

        long ticks = Math.max(1, Math.round(def.reloadSeconds * 20));
        reloadTasks.put(uuid, player.getScheduler().runDelayed(plugin, task -> finishReload(player), null, ticks));
    }

    private void finishReload(Player player) {
        UUID uuid = player.getUniqueId();
        reloadTasks.remove(uuid);
        PendingReload pending = reloads.remove(uuid);
        if (pending == null || !player.isOnline()) {
            return;
        }
        ItemStack gun = findGunBySerial(player, pending.serial());
        if (gun == null) {
            return;
        }
        GunInstance instance = module.guns().read(gun);
        if (instance == null || !instance.defOk()) {
            return;
        }
        GunDefinition def = pending.def();
        int need = def.magSize - instance.ammoInMag();
        if (need <= 0) {
            module.context().notifications().action(player, "&7The magazine is full.");
            return;
        }
        int taken = module.guns().consumeAmmo(player, def.gunType().ammo(), need);
        int loaded = Math.min(def.magSize, instance.ammoInMag() + taken);
        module.guns().updateGun(gun, def, instance.serial(), instance.durability(), loaded);
        boolean pose = module.thirdPersonGunPose().isPoseGun(gun);
        module.thirdPersonGunPose().set(player, pose);
        Bukkit.getScheduler().runTask(module.context().plugin(), () -> {
            if (player.isOnline() && module.thirdPersonGunPose().isPoseGun(player.getInventory().getItemInMainHand())) {
                module.thirdPersonGunPose().set(player, true);
                module.thirdPersonGunPose().refresh(player);
            }
        });
        if (loaded < def.magSize) {
            module.playDry(player);
            module.context().notifications().action(player, "&cNot enough ammo - loaded &f" + loaded
                    + "&c of &f" + def.magSize + "&c.");
            return;
        }
        module.context().notifications().action(player, "&aReloaded " + def.name + ".");
    }

    private ItemStack findGunBySerial(Player player, String serial) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (serial.equals(ItemBuilder.tag(item, GunManager.GUN_SERIAL_KEY))) {
                return item;
            }
        }
        return null;
    }

    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (item == null || !module.guns().isGun(item)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (reloadTasks.containsKey(uuid)) {
            return;
        }
        GunInstance instance = module.guns().read(item);
        if (instance == null || !instance.defOk()) {
            return;
        }
        if (instance.ammoInMag() >= instance.def().magSize) {
            module.context().notifications().action(player, "&7The magazine is full.");
            return;
        }
        startReload(player, instance);
    }

    // --- Cleanup ----------------------------------------------------------

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        ItemStack held = player.getInventory().getItem(event.getNewSlot());
        if (held != null && module.guns().isGun(held)) {
            GunInstance instance = module.guns().read(held);
            if (instance != null && instance.defOk()) {
                boolean pose = module.thirdPersonGunPose().isPoseGun(held);
                module.thirdPersonGunPose().set(player, pose);
                Bukkit.getScheduler().runTask(module.context().plugin(), () -> {
                    if (player.isOnline()) {
                        module.thirdPersonGunPose().set(player,
                                module.thirdPersonGunPose().isPoseGun(player.getInventory().getItemInMainHand()));
                    }
                });
            }
            return;
        }
        if (aiming.contains(uuid)) {
            aiming.remove(uuid);
            stopAimZoom(player);
        }
        module.thirdPersonGunPose().set(player, false);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        aiming.remove(uuid);
        ScheduledTask task = aimTasks.remove(uuid);
        if (task != null) {
            cancelQuietly(task);
            FovZoom.reset(player);
        }
        nextFire.remove(uuid);
        reloads.remove(uuid);
        ScheduledTask reloadTask = reloadTasks.remove(uuid);
        if (reloadTask != null) cancelQuietly(reloadTask);
        lastNeedAmmoMessage.remove(uuid);
        lastReloadMessage.remove(uuid);
        cleanupProjectilesByShooter(uuid);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        aiming.remove(uuid);
        nextFire.remove(uuid);
        reloads.remove(uuid);
        ScheduledTask task = reloadTasks.remove(uuid);
        if (task != null) {
            cancelQuietly(task);
        }
        ScheduledTask aimTask = aimTasks.remove(uuid);
        if (aimTask != null) {
            cancelQuietly(aimTask);
        }
        lastNeedAmmoMessage.remove(uuid);
        lastReloadMessage.remove(uuid);
        cleanupProjectilesByShooter(uuid);
        module.thirdPersonGunPose().set(event.getPlayer(), false);
    }

    // --- Helpers ----------------------------------------------------------

    private static void cancelQuietly(ScheduledTask task) {
        if (task == null) return;
        try {
            task.cancel();
        } catch (IllegalStateException ignored) {
        }
    }

    private Vector direction(double yaw, double pitch) {
        double radYaw = Math.toRadians(yaw);
        double radPitch = Math.toRadians(pitch);
        double dx = -Math.sin(radYaw) * Math.cos(radPitch);
        double dy = -Math.sin(radPitch);
        double dz = Math.cos(radYaw) * Math.cos(radPitch);
        return new Vector(dx, dy, dz).normalize();
    }

    private double rand(double min, double max) {
        return min + Math.random() * (max - min);
    }

    private double parseDouble(String value, double def) {
        try {
            return value == null ? def : Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private Player parseShooter(String id) {
        if (id == null) {
            return null;
        }
        try {
            return Bukkit.getPlayer(UUID.fromString(id));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
