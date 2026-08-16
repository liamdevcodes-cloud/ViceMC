package net.vicemc.modules.lifestyle;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.Json;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the lifestyle loop: rest/thirst drain, exhaustion and dehydration
 * penalties, fitness-based walk speed, sleeping, eating/drinking custom
 * items and the empty-bottle deposit.
 */
public final class LifestyleManager implements Listener {

    private final ViceModuleContext ctx;
    private final YamlConfig config;
    private final LifestyleItems items;
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Set<UUID> exhausted = ConcurrentHashMap.newKeySet();
    private final Map<UUID, RestSession> resting = new ConcurrentHashMap<>();
    private ScheduledTask task;

    private static final class RestSession {
        final Location bed;
        final long startedAt;

        RestSession(Location bed, long startedAt) {
            this.bed = bed;
            this.startedAt = startedAt;
        }
    }

    public LifestyleManager(ViceModuleContext ctx, YamlConfig config, LifestyleItems items) {
        this.ctx = ctx;
        this.config = config;
        this.items = items;
    }

    public void start() {
        long seconds = Math.max(1, config.getInt("tick-seconds", 5));
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(),
                scheduled -> tick(seconds), 20L, seconds * 20L);
    }

    public void stop() {
        if (task != null) {
            cancelQuietly(task);
            task = null;
        }
        resting.clear();
    }

    public PlayerState state(UUID uuid) {
        return states.get(uuid);
    }

    public void saveAll() {
        states.values().forEach(this::save);
    }

    // --- State loading / persistence --------------------------------------

    public PlayerState getOrLoad(UUID uuid) {
        return states.computeIfAbsent(uuid, u -> {
            PlayerState loaded = load(u);
            if (loaded == null) {
                loaded = new PlayerState(u);
            }
            loaded.drainOffline(config);
            return loaded;
        });
    }

    private PlayerState load(UUID uuid) {
        return ctx.storage().getModuleData("lifestyle", "player:" + uuid)
                .map(json -> Json.fromJson(json, PlayerState.class))
                .orElse(null);
    }

    public void save(PlayerState state) {
        ctx.storage().setModuleData("lifestyle", "player:" + state.uuid, Json.toJson(state));
    }

    // --- Events -----------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        PlayerState state = getOrLoad(event.getPlayer().getUniqueId());
        applySpeed(event.getPlayer(), state);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        endRest(event.getPlayer().getUniqueId(), null);
        PlayerState state = states.remove(event.getPlayer().getUniqueId());
        if (state != null) {
            save(state);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBedEnter(PlayerBedEnterEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (event.getBedEnterResult() == PlayerBedEnterEvent.BedEnterResult.NOT_POSSIBLE_NOW) {
            event.setCancelled(true);
            event.setUseBed(Event.Result.DENY);
            if (!config.getBoolean("rest.day-rest-enabled", true)) {
                return;
            }
            if (resting.containsKey(uuid)) {
                ctx.notifications().action(player, "&7You are already resting.");
                return;
            }
            startRest(player, event.getBed().getLocation());
            return;
        }
        endRest(uuid, null);
        event.setCancelled(false);
        PlayerState state = getOrLoad(uuid);
        state.inBed = true;
        state.bedEnteredAt = System.currentTimeMillis();
    }

    private void startRest(Player player, Location bed) {
        resting.put(player.getUniqueId(), new RestSession(bed, System.currentTimeMillis()));
        ctx.notifications().action(player, "&bYou begin resting. Stay near the bed and your sleep will bank up.");
    }

    private void endRest(UUID uuid, Player player) {
        RestSession session = resting.remove(uuid);
        if (session == null) {
            return;
        }
        if (player == null) {
            player = Bukkit.getPlayer(uuid);
        }
        if (player == null || !player.isOnline()) {
            return;
        }
        long seconds = (System.currentTimeMillis() - session.startedAt) / 1000L;
        if (seconds < 3) {
            ctx.notifications().action(player, "&7You didn't rest long enough to bank any sleep.");
            return;
        }
        double gainPerMinute = config.getDouble("rest.sleep-gain-per-minute", 1.0);
        double hoursSlept = (seconds / 60.0) * gainPerMinute;
        PlayerState state = getOrLoad(uuid);
        ctx.notifications().action(player, "&aRested for &f" + fmtHours(hoursSlept)
                + "&a. Rest: &f" + pct(state.rest) + "%&a.");
    }

    @EventHandler
    public void onBedLeave(PlayerBedLeaveEvent event) {
        PlayerState state = getOrLoad(event.getPlayer().getUniqueId());
        if (!state.inBed) {
            return;
        }
        state.inBed = false;
        long seconds = (System.currentTimeMillis() - state.bedEnteredAt) / 1000L;
        if (seconds < 3) {
            return;
        }
        double gainPerMinute = config.getDouble("rest.sleep-gain-per-minute", 1.0);
        double hoursSlept = (seconds / 60.0) * gainPerMinute;
        state.addSleep(seconds, config);
        save(state);
        ctx.notifications().action(event.getPlayer(),
                "&aSlept for &f" + fmtHours(hoursSlept) + "&a. Rest: &f" + pct(state.rest) + "%&a.");
    }

    private String fmtHours(double hours) {
        return hours >= 10 ? (int) Math.round(hours) + "h" : String.format("%.1fh", hours);
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        String itemId = ItemBuilder.tag(item, items.itemKey());
        if (itemId != null) {
            LifestyleItem def = items.byId(itemId).orElse(null);
            if (def != null) {
                event.setCancelled(true);
                applyCustom(player, def, item);
            }
            return;
        }
        Double fitness = items.vanillaFitness(item.getType());
        if (fitness != null) {
            PlayerState state = getOrLoad(player.getUniqueId());
            double before = state.fitness;
            state.addFitness(fitness);
            save(state);
            if (state.fitness != before) {
                msgFitness(player, state.fitness, fitness);
            }
        }
    }

    private void applyCustom(Player player, LifestyleItem def, ItemStack consumed) {
        PlayerState state = getOrLoad(player.getUniqueId());
        if (def.hunger > 0) {
            player.setFoodLevel(Math.min(20, player.getFoodLevel() + def.hunger));
        }
        if (def.saturation > 0) {
            player.setSaturation(Math.min(20, player.getSaturation() + (float) def.saturation));
        }
        if (def.thirstRestore > 0) {
            state.addThirst(def.thirstRestore);
        }
        if (def.restRestore > 0) {
            state.addRest(def.restRestore);
            ctx.notifications().action(player, "&aYou feel energized - that sleep can wait for a while.");
        }
        double before = state.fitness;
        if (def.fitness != 0) {
            state.addFitness(def.fitness);
        }
        save(state);
        if (state.fitness != before) {
            msgFitness(player, state.fitness, def.fitness);
        }
        if (def.leavesEmptyBottle) {
            giveEmptyBottle(player);
        }
        removeFromHands(player, consumed);
    }

    private void giveEmptyBottle(Player player) {
        ItemStack bottle = items.emptyBottle();
        Map<Integer, ItemStack> left = player.getInventory().addItem(bottle);
        if (!left.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left.values().iterator().next());
        }
        ctx.notifications().action(player, "&bYour empty bottle is refundable at the deposit (/lifestyle).");
    }

    private void removeFromHands(Player player, ItemStack consumed) {
        String tag = ItemBuilder.tag(consumed, items.itemKey());
        for (ItemStack stack : new ItemStack[]{
                player.getInventory().getItemInMainHand(),
                player.getInventory().getItemInOffHand()}) {
            if (stack != null && stack.getAmount() > 0 && matches(stack, tag)) {
                stack.setAmount(stack.getAmount() - 1);
                return;
            }
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getAmount() > 0 && matches(stack, tag)) {
                stack.setAmount(stack.getAmount() - 1);
                return;
            }
        }
    }

    private boolean matches(ItemStack stack, String tag) {
        return tag != null && tag.equals(ItemBuilder.tag(stack, items.itemKey()));
    }

    private void msgFitness(Player player, double level, double delta) {
        if (delta > 0) {
            ctx.notifications().action(player, "&aThat was good for your fitness. Fitness: &f" + pct(level) + "%&a.");
        } else {
            ctx.notifications().action(player, "&cThat junk hurt your fitness. Fitness: &f" + pct(level) + "%&c.");
        }
    }

    // --- Bottle deposit ---------------------------------------------------

    public int countEmptyBottles(Player player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && items.isEmptyBottle(stack)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    /** Turns in every refundable bottle and pays out the deposit. */
    public int turnInBottles(Player player) {
        int count = countEmptyBottles(player);
        if (count == 0) {
            ctx.notifications().msg(player, "&7You have no empty bottles to turn in.");
            return 0;
        }
        double refund = config.getDouble("bottle.refund", 25) * count;
        var result = ctx.economy().deposit(player.getUniqueId(), refund, "bottle deposit");
        if (!result.success()) {
            ctx.notifications().warn(player, "Deposit failed: " + result.detail());
            return 0;
        }
        removeEmptyBottles(player);
        ctx.notifications().msg(player, "&aTurned in &f" + count + "&a empty bottle(s) for &6"
                + net.vicemc.api.util.GuiKit.fmt(refund) + "&a.");
        return count;
    }

    private void removeEmptyBottles(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && items.isEmptyBottle(stack)) {
                stack.setAmount(0);
            }
        }
    }

    // --- Daily loop -------------------------------------------------------

    /** Dispatches the per-player drain/warning loop onto each player's own thread. */
    private void tick(long seconds) {
        double radius = config.getDouble("rest.day-rest-radius", 3);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(ctx.plugin(), task -> tickPlayer(player, seconds, radius), null);
        }
    }

    private void tickPlayer(Player player, long seconds, double radius) {
        double warnRest = config.getDouble("rest.warn-below", 25);
        double warnThirst = config.getDouble("thirst.warn-below", 25);
        double warnFitness = config.getDouble("fitness.warn-below", 25);
        PotionEffectType exhaustedType = exhaustedEffect();
        int exhaustedAmp = config.getInt("rest.exhausted-amplifier", 0);

        PlayerState state = getOrLoad(player.getUniqueId());
        state.drain(seconds, config);

        if (!state.warnedRest && state.rest <= warnRest) {
            state.warnedRest = true;
            ctx.notifications().action(player, "&eYou feel exhausted. Find a bed to sleep.");
        } else if (state.warnedRest && state.rest > warnRest * 1.5) {
            state.warnedRest = false;
        }
        if (!state.warnedThirst && state.thirst <= warnThirst) {
            state.warnedThirst = true;
            ctx.notifications().action(player, "&bYou are getting thirsty. Drink some water.");
        } else if (state.warnedThirst && state.thirst > warnThirst * 1.5) {
            state.warnedThirst = false;
        }
        if (!state.warnedFitness && state.fitness <= warnFitness) {
            state.warnedFitness = true;
            ctx.notifications().action(player, "&cYour fitness is dropping. Eat healthy food.");
        } else if (state.warnedFitness && state.fitness > warnFitness * 1.5) {
            state.warnedFitness = false;
        }

        UUID uuid = player.getUniqueId();
        if (state.rest <= 0) {
            if (exhausted.add(uuid) && exhaustedType != null) {
                player.addPotionEffect(new PotionEffect(exhaustedType, 200, exhaustedAmp, false, false, true));
            }
        } else if (exhausted.remove(uuid)) {
            player.removePotionEffect(exhaustedType);
        }

        applySpeed(player, state);
        processResting(player, state, seconds, radius);
    }

    private void processResting(Player player, PlayerState state, long seconds, double radius) {
        UUID uuid = player.getUniqueId();
        RestSession session = resting.get(uuid);
        if (session == null) {
            return;
        }
        if (!player.getWorld().equals(session.bed.getWorld())
                || player.getLocation().distance(session.bed) > radius) {
            endRest(uuid, player);
            return;
        }
        state.addSleep(seconds, config);
        save(state);
        if (state.rest >= 100) {
            endRest(uuid, player);
        }
    }

    public void applySpeed(Player player, PlayerState state) {
        double factor = state.speedFactor(config);
        float speed = (float) (0.2 * factor);
        if (speed > 1.0f) {
            speed = 1.0f;
        }
        if (speed < 0.01f) {
            speed = 0.01f;
        }
        if (Math.abs(player.getWalkSpeed() - speed) > 0.0005f) {
            player.setWalkSpeed(speed);
        }
    }

    private PotionEffectType exhaustedEffect() {
        PotionEffectType effect = PotionEffectType.getByName(config.getString("rest.exhausted-effect", "SLOWNESS"));
        return effect != null ? effect : PotionEffectType.SLOWNESS;
    }

    public String pct(double value) {
        return String.valueOf((int) Math.round(value));
    }

    private static void cancelQuietly(ScheduledTask task) {
        try {
            task.cancel();
        } catch (IllegalStateException ignored) {
        }
    }
}
