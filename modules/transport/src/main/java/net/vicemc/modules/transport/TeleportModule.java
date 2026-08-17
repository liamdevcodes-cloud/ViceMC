package net.vicemc.modules.transport;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.model.TransactionResult;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TeleportModule implements ViceModule {

    static final NamespacedKey LINKER_KEY = NamespacedKey.fromString("vicemc:linker");
    private static final int PAD_SEARCH_RADIUS = 3;

    private ViceModuleContext ctx;
    private YamlConfig config;
    private Material padMaterial;
    private final Map<String, Teleporter> teleporters = new HashMap<>();
    private final Map<UUID, ActiveTeleport> activeTeleports = new ConcurrentHashMap<>();
    private final Map<UUID, Location> linkingSource = new ConcurrentHashMap<>();
    final Map<UUID, PendingLink> pendingLinks = new ConcurrentHashMap<>();

    @Override public String id() { return "transport"; }
    @Override public String displayName() { return "Vice Transport"; }
    @Override public String version() { return "1.0.0"; }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("teleport.yml");
        this.padMaterial = parseMaterial(config.getString("pad-block", "LODESTONE"));
        loadTeleporters();

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("transport")
                .aliases("tp")
                .permission("vicemc.transport.admin")
                .description("Transport teleporter admin commands")
                .executes(this::onCommand)
                .tabulates((c, a) -> {
                    if (a.size() <= 1) return List.of("reload", "list", "unlink", "give");
                    return List.of();
                })
                .build());

        Bukkit.getPluginManager().registerEvents(new TeleportListener(), ctx.plugin());
        ctx.logger().info("Transport module ready. " + teleporters.size() + " teleporter(s) loaded.");
    }

    @Override
    public void onDisable() {
        activeTeleports.values().forEach(t -> {
            t.cancel();
            Player player = Bukkit.getPlayer(t.playerUuid);
            if (player != null && player.isOnline()) {
                player.removePotionEffect(PotionEffectType.DARKNESS);
                player.showBossBar(t.bossBar);
            }
        });
        activeTeleports.clear();
        linkingSource.clear();
    }

    public ViceModuleContext context() { return ctx; }

    // ===== Config =====

    private void loadTeleporters() {
        teleporters.clear();
        ConfigurationSection section = config.getSection("teleporters");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection ts = section.getConfigurationSection(key);
            if (ts == null) continue;
            String destWorld = ts.getString("dest-world");
            if (destWorld == null) continue;
            teleporters.put(key, new Teleporter(
                    destWorld,
                    ts.getInt("dest-x"), ts.getInt("dest-y"), ts.getInt("dest-z"),
                    ts.getInt("travel-time", 5),
                    ts.getDouble("price", 0),
                    ts.getString("name", key)));
        }
    }

    void saveTeleporters() {
        config.set("teleporters", null);
        for (Map.Entry<String, Teleporter> entry : teleporters.entrySet()) {
            Teleporter t = entry.getValue();
            String p = "teleporters." + entry.getKey();
            config.set(p + ".dest-world", t.destWorld);
            config.set(p + ".dest-x", t.destX);
            config.set(p + ".dest-y", t.destY);
            config.set(p + ".dest-z", t.destZ);
            config.set(p + ".travel-time", t.travelTime);
            config.set(p + ".price", t.price);
            config.set(p + ".name", t.displayName);
        }
        config.save();
    }

    // ===== Lookup =====

    String locKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    Teleporter findByPad(Location padLoc) {
        return teleporters.get(locKey(padLoc));
    }

    // ===== Pad helpers =====

    Location findPadBelow(Block plateBlock) {
        Block below = plateBlock.getRelative(0, -1, 0);
        if (below.getType() == padMaterial) {
            return below.getLocation();
        }
        return null;
    }

    private Material parseMaterial(String name) {
        if (name == null || name.isBlank()) return Material.LODESTONE;
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return Material.LODESTONE;
        }
    }

    // ===== Activation =====

    void activate(Player player, Teleporter teleporter) {
        UUID uuid = player.getUniqueId();
        if (activeTeleports.containsKey(uuid)) return;

        if (teleporter.price > 0) {
            double bal = ctx.economy().balance(uuid);
            if (bal < teleporter.price) {
                ctx.notifications().msg(player,
                        "&cYou need &a$" + String.format("%.2f", teleporter.price)
                        + "&c to use this teleporter. Balance: &a$" + String.format("%.2f", bal) + "&c.");
                return;
            }
        }

        int ticks = teleporter.travelTime * 20;
        BossBar bossBar = BossBar.bossBar(
                Text.color("&e" + teleporter.displayName),
                0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        player.showBossBar(bossBar);

        if (teleporter.travelTime >= 2) {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.DARKNESS, ticks + 20, 0, false, false, false));
        }

        ActiveTeleport active = new ActiveTeleport(uuid, teleporter, bossBar, ticks);
        activeTeleports.put(uuid, active);
        active.start();
    }

    void cancelTeleport(Player player) {
        UUID uuid = player.getUniqueId();
        ActiveTeleport active = activeTeleports.remove(uuid);
        if (active == null) return;
        active.cancel();
        player.removePotionEffect(PotionEffectType.DARKNESS);
        player.showBossBar(active.bossBar);
        ctx.notifications().msg(player, "&7Teleport cancelled.");
    }

    // ===== Signs =====

    void updateSignAbove(Location padLoc, Teleporter teleporter) {
        Block signBlock = padLoc.getBlock().getRelative(0, 2, 0);
        if (!(signBlock.getState() instanceof Sign sign)) return;

        sign.setLine(0, "[Teleport]");
        if (teleporter != null) {
            sign.setLine(1, teleporter.displayName);
            if (teleporter.price > 0) {
                sign.setLine(2, Text.moneyPlain(teleporter.price));
            } else {
                sign.setLine(2, "Free");
            }
            sign.setLine(3, teleporter.travelTime + "s trip");
        } else {
            sign.setLine(1, "---");
            sign.setLine(2, "");
            sign.setLine(3, "Use linker tool");
        }
        sign.update(true, false);
    }

    void clearSignAbove(Location padLoc) {
        Block signBlock = padLoc.getBlock().getRelative(0, 2, 0);
        if (!(signBlock.getState() instanceof Sign sign)) return;
        sign.setLine(0, "[Teleport]");
        sign.setLine(1, "---");
        sign.setLine(2, "");
        sign.setLine(3, "Use linker tool");
        sign.update(true, false);
    }

    // ===== Linking tool =====

    public static ItemStack linkerTool() {
        return ItemBuilder.of(Material.STICK)
                .name("&6Teleporter Linker")
                .lore(
                        "&7Right-click a pad to start linking.",
                        "&7Right-click another pad to connect.",
                        "&7Sneak + right-click to unlink a pad.",
                        "&8Use /transport give to obtain."
                )
                .tag(LINKER_KEY, "true")
                .glow()
                .build();
    }

    public static boolean isLinker(ItemStack item) {
        return ItemBuilder.hasTag(item, LINKER_KEY, "true");
    }

    // ===== Commands =====

    private void onCommand(net.vicemc.api.service.CommandContext c) {
        if (c.size() == 0) {
            c.msg("&6/transport reload | list | unlink | give");
            return;
        }
        switch (c.arg(0).toLowerCase()) {
            case "reload" -> {
                config.reload();
                padMaterial = parseMaterial(config.getString("pad-block", "LODESTONE"));
                loadTeleporters();
                c.msg("&aReloaded teleport.yml: " + teleporters.size() + " teleporter(s).");
            }
            case "list" -> listTeleporters(c);
            case "unlink" -> {
                if (!c.isPlayer()) { c.error("Only players can unlink."); return; }
                unlinkPad(c.player());
            }
            case "give" -> {
                if (!c.isPlayer()) { c.error("Only players can receive the linker tool."); return; }
                giveLinker(c.player());
            }
            default -> c.msg("&6/transport reload | list | unlink | give");
        }
    }

    private void listTeleporters(net.vicemc.api.service.CommandContext c) {
        Set<String> shown = new HashSet<>();
        List<Teleporter> unique = new ArrayList<>();
        for (Teleporter t : teleporters.values()) {
            if (shown.add(t.displayName)) unique.add(t);
        }
        if (unique.isEmpty()) {
            c.msg("&7No teleporters configured.");
            return;
        }
        c.msg("&6Teleporters (" + unique.size() + "):");
        for (Teleporter t : unique) {
            String dest = t.destWorld + " " + t.destX + "," + t.destY + "," + t.destZ;
            c.msg("  &f" + t.displayName + " &7-> &f" + dest
                    + " &8(" + t.travelTime + "s, "
                    + (t.price > 0 ? "$" + String.format("%.2f", t.price) : "free") + ")");
        }
    }

    private void unlinkPad(Player player) {
        Block target = player.getTargetBlockExact(5);
        if (target == null || target.getType() != padMaterial) {
            ctx.notifications().warn(player, "&cLook at a " + padMaterial.name() + " pad and try again.");
            return;
        }
        String key = locKey(target.getLocation());
        Teleporter removed = teleporters.remove(key);
        if (removed == null) {
            ctx.notifications().warn(player, "&cThis pad is not linked.");
            return;
        }
        clearSignAbove(target.getLocation());
        saveTeleporters();
        ctx.notifications().msg(player, "&7Pad unlinked.");
    }

    private void giveLinker(Player player) {
        var leftover = player.getInventory().addItem(linkerTool());
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
        }
        ctx.notifications().msg(player, "&aTeleporter linker tool added to your inventory.");
    }

    // ===== Pending link =====

    static final class PendingLink {
        final Location source;
        final Location destination;
        PendingLink(Location source, Location destination) {
            this.source = source;
            this.destination = destination;
        }
    }

    private void handleLinkInput(Player player, PendingLink link, String input) {
        UUID uuid = player.getUniqueId();
        pendingLinks.remove(uuid);

        if (input.equalsIgnoreCase("cancel")) {
            ctx.notifications().msg(player, "&7Linking cancelled.");
            return;
        }

        String name = input.trim();
        double price = config.getDouble("default-price", 0);
        int travelTime = config.getInt("default-travel-time", 5);

        Teleporter teleporter = new Teleporter(
                link.destination.getWorld().getName(),
                link.destination.getBlockX(),
                link.destination.getBlockY(),
                link.destination.getBlockZ(),
                travelTime, price, name);

        String sourceKey = locKey(link.source);
        String destKey = locKey(link.destination);
        teleporters.put(sourceKey, teleporter);
        teleporters.put(destKey, teleporter);

        updateSignAbove(link.source, teleporter);
        updateSignAbove(link.destination, teleporter);
        saveTeleporters();

        ctx.notifications().msg(player, "&aLinked &f" + name + "&a!");
        ctx.notifications().msg(player, "&7Price: &f" + (price > 0 ? "$" + String.format("%.2f", price) : "Free")
                + " &7| Travel time: &f" + travelTime + "s");
        ctx.notifications().msg(player, "&7Edit teleport.yml to change price/travel-time.");
    }

    // ===== Data =====

    static final class Teleporter {
        final String destWorld;
        final int destX, destY, destZ;
        final int travelTime;
        final double price;
        final String displayName;

        Teleporter(String destWorld, int destX, int destY, int destZ,
                   int travelTime, double price, String displayName) {
            this.destWorld = destWorld;
            this.destX = destX;
            this.destY = destY;
            this.destZ = destZ;
            this.travelTime = travelTime;
            this.price = price;
            this.displayName = displayName;
        }

        Location destination() {
            var world = Bukkit.getWorld(destWorld);
            if (world == null) return null;
            return new Location(world, destX + 0.5, destY, destZ + 0.5);
        }
    }

    // ===== Active teleport =====

    final class ActiveTeleport {
        final UUID playerUuid;
        final Teleporter teleporter;
        final BossBar bossBar;
        final int totalTicks;
        int elapsed;
        BukkitTask task;

        ActiveTeleport(UUID playerUuid, Teleporter teleporter, BossBar bossBar, int totalTicks) {
            this.playerUuid = playerUuid;
            this.teleporter = teleporter;
            this.bossBar = bossBar;
            this.totalTicks = totalTicks;
        }

        void start() {
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    Player player = Bukkit.getPlayer(playerUuid);
                    if (player == null || !player.isOnline()) {
                        cancel();
                        activeTeleports.remove(playerUuid);
                        return;
                    }

                    elapsed += 5;
                    float progress = Math.min(1f, (float) elapsed / totalTicks);
                    bossBar.progress(progress);

                    int remaining = Math.max(0, (totalTicks - elapsed) / 20);
                    bossBar.name(Text.color("&e" + teleporter.displayName + " &8- &f" + remaining + "s"));

                    if (elapsed >= totalTicks) {
                        cancel();
                        activeTeleports.remove(playerUuid);

                        if (teleporter.price > 0) {
                            TransactionResult result = ctx.economy().withdraw(
                                    playerUuid, teleporter.price, "transport:" + teleporter.displayName);
                            if (!result.success()) {
                                ctx.notifications().warn(player, "&cPayment failed: " + result.detail());
                                player.removePotionEffect(PotionEffectType.DARKNESS);
                                player.showBossBar(bossBar);
                                return;
                            }
                        }

                        Location dest = teleporter.destination();
                        if (dest == null) {
                            ctx.notifications().warn(player, "&cDestination world is not loaded.");
                            player.removePotionEffect(PotionEffectType.DARKNESS);
                            player.showBossBar(bossBar);
                            return;
                        }

                        player.removePotionEffect(PotionEffectType.DARKNESS);
                        player.showBossBar(bossBar);
                        player.teleport(dest);
                        ctx.notifications().msg(player, "&aArrived at &f" + teleporter.displayName + "&a!");
                    }
                }
            }.runTaskTimer(ctx.plugin(), 0L, 5L);
        }

        void cancel() {
            if (task != null && !task.isCancelled()) task.cancel();
        }
    }

    // ===== Event Listener =====

    final class TeleportListener implements Listener {

        @EventHandler
        public void onBlockPlace(BlockPlaceEvent event) {
            if (event.getItemInHand().getType() != padMaterial) return;
            Player player = event.getPlayer();
            if (!player.hasPermission("vicemc.transport.admin")) return;

            Location padLoc = event.getBlock().getLocation();
            Location signLoc = padLoc.clone().add(0, 2, 0);
            Block signBlock = signLoc.getBlock();

            if (signBlock.getType() != Material.AIR && signBlock.getType() != Material.CAVE_AIR) return;

            signBlock.setType(Material.OAK_SIGN);
            if (signBlock.getState() instanceof Sign sign) {
                sign.setLine(0, "[Teleport]");
                sign.setLine(1, "---");
                sign.setLine(2, "");
                sign.setLine(3, "Use linker tool");
                sign.update(true, false);
            }

            teleporters.put(locKey(padLoc), null);
            ctx.notifications().msg(player, "&aTeleporter pad placed! Use the &6/transport give &ato get the linker.");
        }

        @EventHandler
        public void onSignChange(SignChangeEvent event) {
            String line0 = event.getLine(0);
            if (line0 == null || !line0.equalsIgnoreCase("[teleport]")) return;
            Player player = event.getPlayer();
            if (!player.hasPermission("vicemc.transport.admin")) {
                event.setCancelled(true);
                ctx.notifications().warn(player, "&cYou don't have permission to create teleporters.");
            }
        }

        @EventHandler
        public void onPhysicalInteract(PlayerInteractEvent event) {
            if (event.getAction() != Action.PHYSICAL) return;
            Block block = event.getClickedBlock();
            if (block == null) return;
            if (!block.getType().name().endsWith("PRESSURE_PLATE")) return;

            Location padLoc = findPadBelow(block);
            if (padLoc == null) return;

            Teleporter teleporter = findByPad(padLoc);
            if (teleporter == null) {
                ctx.notifications().warn(event.getPlayer(), "&cThis teleporter is not linked yet.");
                return;
            }

            Player player = event.getPlayer();
            if (!player.hasPermission("vicemc.transport.use")) {
                ctx.notifications().warn(player, "&cYou don't have permission to use teleporters.");
                return;
            }

            activate(player, teleporter);
        }

        @EventHandler
        public void onLinkerInteract(PlayerInteractEvent event) {
            if (event.getHand() != EquipmentSlot.HAND) return;
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

            Player player = event.getPlayer();
            ItemStack held = player.getInventory().getItemInMainHand();
            if (!isLinker(held)) return;

            Block block = event.getClickedBlock();
            if (block == null || block.getType() != padMaterial) return;
            event.setCancelled(true);

            Location padLoc = block.getLocation();
            UUID uuid = player.getUniqueId();

            if (player.isSneaking()) {
                Teleporter removed = teleporters.remove(locKey(padLoc));
                if (removed != null) {
                    clearSignAbove(padLoc);
                    saveTeleporters();
                    ctx.notifications().msg(player, "&7Pad unlinked.");
                } else {
                    ctx.notifications().warn(player, "&cThis pad is not linked.");
                }
                return;
            }

            Location source = linkingSource.get(uuid);
            if (source == null) {
                linkingSource.put(uuid, padLoc);
                ctx.notifications().msg(player, "&6Source selected! &7Right-click the destination pad to link.");
                return;
            }

            if (source.equals(padLoc)) {
                linkingSource.remove(uuid);
                ctx.notifications().warn(player, "&cSource and destination are the same pad.");
                return;
            }

            linkingSource.remove(uuid);
            ctx.notifications().msg(player, "&6Now type the destination name in chat (or &ccancel&7):");
            PendingLink link = new PendingLink(source, padLoc);
            pendingLinks.put(uuid, link);
        }

        @EventHandler
        public void onMove(PlayerMoveEvent event) {
            UUID uuid = event.getPlayer().getUniqueId();
            if (!activeTeleports.containsKey(uuid)) return;
            if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                    && event.getFrom().getBlockY() == event.getTo().getBlockY()
                    && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;
            cancelTeleport(event.getPlayer());
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            UUID uuid = event.getPlayer().getUniqueId();
            ActiveTeleport active = activeTeleports.remove(uuid);
            if (active != null) active.cancel();
            linkingSource.remove(uuid);
            pendingLinks.remove(uuid);
        }

        @EventHandler
        public void onChat(AsyncPlayerChatEvent event) {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();
            PendingLink link = pendingLinks.get(uuid);
            if (link == null) return;

            event.setCancelled(true);
            String input = event.getMessage();
            player.getScheduler().run(ctx.plugin(), task -> handleLinkInput(player, link, input), null);
        }
    }
}
