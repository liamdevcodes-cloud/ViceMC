package net.vicemc.modules.heists;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Runs heists: hostage spawning, police-response collapse, random coastal
 * getaway boats, island extraction and helicopter routes back into the world.
 */
public final class HeistManager implements Listener {

    private static final NamespacedKey HOSTAGE_KEY = NamespacedKey.fromString("vicemc:heist_hostage");
    private static final NamespacedKey BOAT_KEY = NamespacedKey.fromString("vicemc:heist_boat");
    private static final NamespacedKey MASK_KEY = NamespacedKey.fromString("vicemc:mask");

    private final ViceModuleContext ctx;
    private final YamlConfig config;
    private final List<HeistSite> sites = new CopyOnWriteArrayList<>();
    private final Map<Integer, Heist> active = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private int nextId = 1;

    public HeistManager(ViceModuleContext ctx, YamlConfig config) {
        this.ctx = ctx;
        this.config = config;
        loadSites();
    }

    private void loadSites() {
        var section = config.getSection("sites");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            HeistSite site = new HeistSite();
            site.id = id;
            site.name = config.getString("sites." + id + ".name", id);
            site.regionTag = config.getString("sites." + id + ".region-tag", "heist:" + id);
            site.payout = config.getDouble("sites." + id + ".payout", 10000);
            site.risk = config.getInt("sites." + id + ".risk", 1);
            site.durationSeconds = config.getInt("sites." + id + ".duration-seconds", 600);
            sites.add(site);
        }
    }

    public List<HeistSite> sites() {
        return sites;
    }

    public HeistSite site(String id) {
        return sites.stream().filter(s -> s.id.equals(id)).findFirst().orElse(null);
    }

    public Optional<Heist> activeFor(UUID uuid) {
        return active.values().stream().filter(h -> h.robbers.contains(uuid)).findFirst();
    }

    public Optional<Heist> activeById(int id) {
        return Optional.ofNullable(active.get(id));
    }

    public int activeCount() {
        return active.size();
    }

    // --- Start / stop -----------------------------------------------------

    public Heist start(Player leader, HeistSite site, List<Player> crew) {
        Heist heist = new Heist();
        heist.id = nextId++;
        heist.siteId = site.id;
        heist.leader = leader.getUniqueId();
        crew.forEach(p -> heist.robbers.add(p.getUniqueId()));
        heist.startedAt = System.currentTimeMillis();
        heist.endsAt = System.currentTimeMillis() + site.durationSeconds * 1000L;
        heist.allMasked = true;
        spawnHostage(heist);
        spawnBoat(heist);
        active.put(heist.id, heist);

        ctx.notifications().broadcast("&4\u26A0 A &f" + site.name + " &4heist is in progress! "
                + "Police hold position while the hostage is active.");
        ctx.events().publish("heist", Map.of("type", "start", "site", site.id, "risk", site.risk));
        startMonitor(heist);
        checkMasks(heist, crew);
        return heist;
    }

    public void abort(UUID uuid, String reason) {
        active.values().stream().filter(h -> h.robbers.contains(uuid)).findFirst()
                .ifPresent(h -> finish(h, "FAILED", reason));
    }

    private void spawnHostage(Heist heist) {
        Location loc = parseLoc(config.getString("hostage-spawn", "world,0,64,0"));
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        Pig hostage = loc.getWorld().spawn(loc, Pig.class, pig -> {
            pig.customName(Text.color("&c\u26A0 HOSTAGE \u26A0"));
            pig.setCustomNameVisible(true);
            pig.setAI(false);
            pig.setInvulnerable(true);
            pig.setSilent(true);
        });
        hostage.getPersistentDataContainer().set(HOSTAGE_KEY,
                org.bukkit.persistence.PersistentDataType.STRING, String.valueOf(heist.id));
        heist.hostageId = hostage.getUniqueId();
    }

    private void spawnBoat(Heist heist) {
        List<String> coastal = config.getStringList("coastal");
        if (coastal.isEmpty()) {
            return;
        }
        String chosen = coastal.get(random.nextInt(coastal.size()));
        Location loc = parseLoc(chosen);
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        Boat boat = loc.getWorld().spawn(loc, Boat.class);
        boat.customName(Text.color("&aGetaway Boat"));
        boat.setCustomNameVisible(true);
        boat.getPersistentDataContainer().set(BOAT_KEY,
                org.bukkit.persistence.PersistentDataType.STRING, String.valueOf(heist.id));
        heist.boatId = boat.getUniqueId();
    }

    private void startMonitor(Heist heist) {
        if (heist.hostageId != null) {
            Entity hostage = Bukkit.getEntity(heist.hostageId);
            if (hostage != null) {
                heist.monitorTask = hostage.getScheduler().runAtFixedRate(ctx.plugin(),
                        task -> tickRunning(heist),
                        () -> finish(heist, "FAILED", "the hostage escaped and the robbery collapsed"),
                        40L, 20L);
                return;
            }
        }
        // No hostage to track (spawn config missing): fall back to a data-only
        // global timer that only expires the heist after its duration.
        heist.monitorTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(),
                task -> {
                    if (heist.phase.equals("RUNNING") && System.currentTimeMillis() >= heist.endsAt) {
                        succeed(heist);
                    }
                }, 40L, 20L);
    }

    private void checkMasks(Heist heist, List<Player> crew) {
        for (Player player : crew) {
            player.getScheduler().run(ctx.plugin(), task -> {
                if (!wearingMask(player)) {
                    heist.allMasked = false;
                }
            }, null);
        }
    }

    private void tickRunning(Heist heist) {
        long now = System.currentTimeMillis();
        if (now >= heist.endsAt) {
            succeed(heist);
            return;
        }
        if (hostageLost(heist)) {
            finish(heist, "FAILED", "the hostage escaped and the robbery collapsed");
            return;
        }
        policeReachedHostage(heist, reached -> {
            if (reached) {
                finish(heist, "FAILED", "police reached the hostage and the robbery collapsed");
            }
        });
    }

    private boolean hostageLost(Heist heist) {
        if (heist.hostageId == null) {
            return false;
        }
        Entity hostage = Bukkit.getEntity(heist.hostageId);
        return hostage == null || !hostage.isValid() || hostage.isDead();
    }

    private void policeReachedHostage(Heist heist, Consumer<Boolean> result) {
        if (heist.hostageId == null) {
            result.accept(false);
            return;
        }
        Entity hostage = Bukkit.getEntity(heist.hostageId);
        if (hostage == null) {
            result.accept(false);
            return;
        }
        World world = hostage.getWorld();
        Location loc = hostage.getLocation();
        int radius = config.getInt("heist.hostage-zone-radius", 12);
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            result.accept(false);
            return;
        }
        AtomicInteger pending = new AtomicInteger(players.size());
        AtomicInteger reached = new AtomicInteger();
        Runnable done = () -> {
            if (pending.decrementAndGet() == 0) {
                hostage.getScheduler().run(ctx.plugin(),
                        task -> result.accept(reached.get() > 0), null);
            }
        };
        for (Player player : players) {
            player.getScheduler().run(ctx.plugin(),
                    task -> {
                        if (reached.get() == 0 && player.hasPermission("vicemc.police")
                                && player.getWorld().equals(world)
                                && player.getLocation().distanceSquared(loc) <= radius * radius) {
                            reached.incrementAndGet();
                        }
                        done.run();
                    }, done);
        }
    }

    private void succeed(Heist heist) {
        HeistSite site = site(heist.siteId);
        double share = site == null ? 0 : site.payout / Math.max(1, heist.robbers.size());
        for (UUID robber : heist.robbers) {
            Player player = Bukkit.getPlayer(robber);
            if (player != null) {
                ctx.economy().deposit(robber, share, "heist payout: " + heist.siteId);
                ctx.notifications().msg(player, "&aHeist succeeded! You earned &f" + Text.moneyPlain(share) + "&a.");
            }
        }
        ctx.notifications().broadcast("&6\u2714 The heist at "
                + (site == null ? heist.siteId : site.name) + " succeeded. The crew got away!");
        ctx.events().publish("heist", Map.of("type", "success", "site", heist.siteId, "payout", share));
        finish(heist, "SUCCESS", null);
    }

    private void finish(Heist heist, String phase, String message) {
        if (!active.containsKey(heist.id)) {
            return;
        }
        heist.phase = phase;
        if (heist.monitorTask != null) {
            cancelQuietly(heist.monitorTask);
            heist.monitorTask = null;
        }
        cleanup(heist);
        if (message != null) {
            ctx.notifications().broadcast("&c\u26A0 The heist at "
                    + (site(heist.siteId) == null ? heist.siteId : site(heist.siteId).name)
                    + " failed - " + message + ". Police are authorized to use lethal force.");
        }
        ctx.events().publish("heist", Map.of("type", "end", "site", heist.siteId, "phase", phase));
    }

    private void cleanup(Heist heist) {
        if (heist.hostageId != null) {
            Entity hostage = Bukkit.getEntity(heist.hostageId);
            if (hostage != null) {
                hostage.getScheduler().run(ctx.plugin(), task -> {
                    Entity current = Bukkit.getEntity(heist.hostageId);
                    if (current != null) {
                        current.remove();
                    }
                }, null);
            }
        }
        if (heist.boatId != null) {
            Entity boat = Bukkit.getEntity(heist.boatId);
            if (boat != null) {
                boat.getScheduler().run(ctx.plugin(), task -> {
                    Entity current = Bukkit.getEntity(heist.boatId);
                    if (current != null) {
                        current.remove();
                    }
                }, null);
            }
        }
        active.remove(heist.id);
    }

    // --- Getaway boat -----------------------------------------------------

    @EventHandler
    public void onBoatInteract(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        String tag = entity.getPersistentDataContainer().get(BOAT_KEY,
                org.bukkit.persistence.PersistentDataType.STRING);
        if (tag == null) {
            return;
        }
        Heist heist = active.get(Integer.parseInt(tag));
        if (heist == null || !heist.robbers.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        escape(heist);
    }

    private void escape(Heist heist) {
        Location island = parseLoc(config.getString("island", "world,0,64,500"));
        if (island == null || island.getWorld() == null) {
            return;
        }
        for (UUID robber : heist.robbers) {
            Player player = Bukkit.getPlayer(robber);
            if (player != null) {
                player.getScheduler().run(ctx.plugin(), task -> {
                    player.teleport(island);
                    ctx.notifications().msg(player, "&aYou escaped on the getaway boat. "
                            + "Use &e/heist return <route>&a to take a helicopter back.");
                }, null);
            }
        }
        heist.phase = "ESCAPED";
        ctx.notifications().broadcast("&6\u2694 The crew escaped to the island on the getaway boat!");
        ctx.events().publish("heist.escaped", Map.of(
                "type", "escaped",
                "site", heist.siteId,
                "leader", heist.leader.toString(),
                "masked", heist.allMasked,
                "members", heist.robbers.size()));
        cleanup(heist);
    }

    public void returnToWorld(Player player, String route) {
        String locString = config.getString("helicopter-routes." + route, null);
        if (locString == null) {
            return;
        }
        Location target = parseLoc(locString);
        if (target == null || target.getWorld() == null) {
            return;
        }
        player.teleport(target);
        ctx.notifications().msg(player, "&aHelicopter arrived - you are back in the city.");
    }

    // --- Mask detection ---------------------------------------------------

    public boolean wearingMask(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet == null) {
            return false;
        }
        if ("true".equals(ItemBuilder.tag(helmet, MASK_KEY))) {
            return true;
        }
        if (config.getStringList("mask-materials").contains(helmet.getType().name())) {
            var meta = helmet.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                String name = org.bukkit.ChatColor.stripColor(meta.getDisplayName()).toLowerCase();
                return name.contains("mask") || name.contains("balaclava");
            }
        }
        return false;
    }

    public Location parseLoc(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 4) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        double x = Double.parseDouble(parts[1]);
        double y = Double.parseDouble(parts[2]);
        double z = Double.parseDouble(parts[3]);
        float yaw = parts.length > 4 ? Float.parseFloat(parts[4]) : 0f;
        float pitch = parts.length > 5 ? Float.parseFloat(parts[5]) : 0f;
        return new Location(world, x, y, z, yaw, pitch);
    }

    public List<String> helicopterRoutes() {
        var section = config.getSection("helicopter-routes");
        return section == null ? List.of() : new ArrayList<>(section.getKeys(false));
    }

    private static void cancelQuietly(ScheduledTask task) {
        try {
            task.cancel();
        } catch (IllegalStateException ignored) {
        }
    }
}
