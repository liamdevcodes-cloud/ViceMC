package net.vicemc.modules.serial;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.Json;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Registry for limited-run serialized items. Every copy of a run is tracked by
 * a unique id; items are stamped with PDC tags and serial lore. Runs and
 * individual copies can be voided, which invalidates (and optionally removes)
 * every matching item still in circulation.
 */
public final class SerialManager {

    public enum Status {
        VALID, INVALID, UNKNOWN
    }

    private static final String RUN_PREFIX = "run:";

    public static final int CHEST_CAPACITY = 51;

    private final ViceModuleContext ctx;
    private final YamlConfig config;
    private final Map<String, SerialRun> runs = new ConcurrentHashMap<>();

    private static final NamespacedKey SERIAL_KEY = NamespacedKey.fromString("vicemc:serial");
    private static final NamespacedKey COPY_KEY = NamespacedKey.fromString("vicemc:serial_copy");
    private static final NamespacedKey INDEX_KEY = NamespacedKey.fromString("vicemc:serial_index");
    private static final NamespacedKey INVALID_KEY = NamespacedKey.fromString("vicemc:serial_invalid");

    private static final List<String> SERIAL_LORE_MARKERS = List.of(
            "Limited edition", "Serial: ", "Limited run",
            "VOIDED", "holds no value");

    public SerialManager(ViceModuleContext ctx, YamlConfig config) {
        this.ctx = ctx;
        this.config = config;
        load();
    }

    // --- Runs -------------------------------------------------------------

    public int maxCount() {
        return config.getInt("max-count", 48);
    }

    public boolean removeOnVoid() {
        return config.getBoolean("remove-on-void", true);
    }

    public List<Integer> counts() {
        List<Integer> result = new ArrayList<>();
        for (String raw : config.getStringList("counts")) {
            try {
                int v = Integer.parseInt(raw.trim());
                if (v > 0) {
                    result.add(v);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return result.isEmpty() ? List.of(1, 2, 4, 8, 12, 16, 24, 48) : result;
    }

    public SerialRun createRun(ItemStack template, int count) {
        SerialRun run = new SerialRun();
        run.serial = nextSerial();
        run.template = Base64.getEncoder().encodeToString(stripSerialTags(template).serializeAsBytes());
        run.count = Math.min(Math.max(1, count), Math.min(maxCount(), CHEST_CAPACITY));
        run.createdAt = System.currentTimeMillis();
        for (int i = 1; i <= run.count; i++) {
            SerialCopy copy = new SerialCopy(UUID.randomUUID().toString(), i);
            run.copies.put(copy.id, copy);
        }
        runs.put(run.serial, run);
        save(run);
        return run;
    }

    public SerialRun run(String serial) {
        return runs.get(serial);
    }

    public List<SerialRun> all() {
        return runs.values().stream()
                .sorted(Comparator.comparing((SerialRun r) -> r.serial).reversed())
                .toList();
    }

    public ItemStack templateOf(SerialRun run) {
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(run.template));
        } catch (Exception ex) {
            return null;
        }
    }

    public void claim(SerialRun run, SerialCopy copy, Player by) {
        copy.claimed = true;
        copy.claimedAt = System.currentTimeMillis();
        copy.claimedBy = by.getUniqueId().toString();
        save(run);
    }

    public SerialRun voidRun(String serial) {
        SerialRun run = runs.get(serial);
        if (run == null) {
            return null;
        }
        run.voided = true;
        for (SerialCopy copy : run.copies.values()) {
            copy.voided = true;
        }
        save(run);
        return run;
    }

    public boolean voidCopy(String serial, String copyId) {
        SerialRun run = runs.get(serial);
        if (run == null) {
            return false;
        }
        SerialCopy copy = run.copyById(copyId);
        if (copy == null) {
            return false;
        }
        copy.voided = true;
        save(run);
        return true;
    }

    public boolean restoreCopy(String serial, String copyId) {
        SerialRun run = runs.get(serial);
        if (run == null) {
            return false;
        }
        SerialCopy copy = run.copyById(copyId);
        if (copy == null || !copy.voided) {
            return false;
        }
        copy.voided = false;
        copy.claimed = false;
        copy.claimedAt = 0;
        copy.claimedBy = null;
        save(run);
        return true;
    }

    // --- Item tags & status ----------------------------------------------

    public boolean isSerialized(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta()
                .getPersistentDataContainer().has(SERIAL_KEY, PersistentDataType.STRING);
    }

    public String serialOf(ItemStack item) {
        return item == null || !item.hasItemMeta() ? null : item.getItemMeta()
                .getPersistentDataContainer().get(SERIAL_KEY, PersistentDataType.STRING);
    }

    public String copyIdOf(ItemStack item) {
        return item == null || !item.hasItemMeta() ? null : item.getItemMeta()
                .getPersistentDataContainer().get(COPY_KEY, PersistentDataType.STRING);
    }

    private boolean isMarkedInvalid(ItemStack item) {
        return item != null && item.hasItemMeta() && "1".equals(item.getItemMeta()
                .getPersistentDataContainer().get(INVALID_KEY, PersistentDataType.STRING));
    }

    public Status statusOf(ItemStack item) {
        if (!isSerialized(item)) {
            return Status.UNKNOWN;
        }
        SerialRun run = runs.get(serialOf(item));
        if (run == null || run.voided) {
            return Status.INVALID;
        }
        String copyId = copyIdOf(item);
        SerialCopy copy = copyId == null ? null : run.copyById(copyId);
        if (copy == null || copy.voided) {
            return Status.INVALID;
        }
        return Status.VALID;
    }

    public ItemStack stampCopy(ItemStack template, SerialRun run, SerialCopy copy) {
        ItemStack item = template.clone();
        item.setAmount(1);
        item.editMeta(meta -> {
            meta.getPersistentDataContainer().set(SERIAL_KEY, PersistentDataType.STRING, run.serial);
            meta.getPersistentDataContainer().set(COPY_KEY, PersistentDataType.STRING, copy.id);
            meta.getPersistentDataContainer().set(INDEX_KEY, PersistentDataType.INTEGER, copy.index);
            meta.getPersistentDataContainer().set(INVALID_KEY, PersistentDataType.STRING, "0");
            List<Component> lore = new ArrayList<>(stripSerialLore(meta.lore()));
            lore.addAll(serialLore(run.count, copy.index, false));
            meta.lore(lore);
        });
        return item;
    }

    public ItemStack markInvalid(ItemStack item) {
        ItemStack copy = item.clone();
        copy.editMeta(meta -> {
            meta.getPersistentDataContainer().set(INVALID_KEY, PersistentDataType.STRING, "1");
            List<Component> lore = new ArrayList<>(stripSerialLore(meta.lore()));
            lore.addAll(serialLore(0, 0, true));
            meta.lore(lore);
        });
        return copy;
    }

    public ItemStack restampValid(ItemStack item) {
        ItemStack copy = item.clone();
        copy.editMeta(meta -> {
            Integer index = meta.getPersistentDataContainer().get(INDEX_KEY, PersistentDataType.INTEGER);
            String serial = meta.getPersistentDataContainer().get(SERIAL_KEY, PersistentDataType.STRING);
            SerialRun run = serial == null ? null : runs.get(serial);
            int count = run == null ? 1 : run.count;
            meta.getPersistentDataContainer().set(INVALID_KEY, PersistentDataType.STRING, "0");
            List<Component> lore = new ArrayList<>(stripSerialLore(meta.lore()));
            lore.addAll(serialLore(count, index == null ? 0 : index, false));
            meta.lore(lore);
        });
        return copy;
    }

    // --- Sweeps -----------------------------------------------------------

    public int removeVoided(Player player) {
        int removed = 0;
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack it = player.getInventory().getItem(i);
            if (it != null && statusOf(it) == Status.INVALID) {
                player.getInventory().setItem(i, null);
                removed++;
            }
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && statusOf(offhand) == Status.INVALID) {
            player.getInventory().setItemInOffHand(null);
            removed++;
        }
        if (player.getOpenInventory() != null && player.getOpenInventory().getTopInventory() != null) {
            var top = player.getOpenInventory().getTopInventory();
            if (top.getType() == org.bukkit.event.inventory.InventoryType.ENDER_CHEST) {
                for (int i = 0; i < top.getSize(); i++) {
                    ItemStack it = top.getItem(i);
                    if (it != null && statusOf(it) == Status.INVALID) {
                        top.setItem(i, null);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    public int scanPlayer(Player player) {
        int count = removeVoided(player);
        if (count > 0) {
            ctx.notifications().warn(player, "Your inventory was scanned: "
                    + count + " voided serialized item(s) were removed from the server.");
        }
        return count;
    }

    /** Scans every online player on their own region thread and reports the total to the reporter. */
    public void scanOnlineAsync(Player reporter, Consumer<Integer> result) {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        int[] removed = {0};
        if (players.isEmpty()) {
            reporter.getScheduler().run(ctx.plugin(), task -> result.accept(0), null);
            return;
        }
        AtomicInteger pending = new AtomicInteger(players.size());
        Runnable done = () -> reporter.getScheduler().run(ctx.plugin(),
                task -> result.accept(removed[0]), null);
        Runnable tick = () -> {
            if (pending.decrementAndGet() == 0) {
                done.run();
            }
        };
        for (Player player : players) {
            player.getScheduler().run(ctx.plugin(),
                    task -> {
                        removed[0] += scanPlayer(player);
                        tick.run();
                    }, tick);
        }
    }

    /** Validates and restamps serial lore for a single player on their own region thread. */
    public int validatePlayer(Player player) {
        int flagged = 0;
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack it = player.getInventory().getItem(i);
            if (!isSerialized(it)) {
                continue;
            }
            Status status = statusOf(it);
            if (status == Status.INVALID && !isMarkedInvalid(it)) {
                player.getInventory().setItem(i, markInvalid(it));
                flagged++;
            } else if (status == Status.VALID && isMarkedInvalid(it)) {
                player.getInventory().setItem(i, restampValid(it));
            }
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (isSerialized(offhand)) {
            Status status = statusOf(offhand);
            if (status == Status.INVALID && !isMarkedInvalid(offhand)) {
                player.getInventory().setItemInOffHand(markInvalid(offhand));
                flagged++;
            } else if (status == Status.VALID && isMarkedInvalid(offhand)) {
                player.getInventory().setItemInOffHand(restampValid(offhand));
            }
        }
        return flagged;
    }

    /** Collects each player's valid copies on their own thread, then voids duplicates on the global thread. */
    public void checkDuplicates() {
        if (!config.getBoolean("duplicate-check", true)) {
            return;
        }
        Map<String, Integer> seen = new ConcurrentHashMap<>();
        Map<String, String> copyToSerial = new ConcurrentHashMap<>();
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            return;
        }
        AtomicInteger pending = new AtomicInteger(players.size());
        Runnable finish = () -> {
            if (pending.decrementAndGet() == 0) {
                Bukkit.getGlobalRegionScheduler().run(ctx.plugin(),
                        task -> processDuplicates(seen, copyToSerial));
            }
        };
        for (Player player : players) {
            player.getScheduler().run(ctx.plugin(),
                    task -> {
                        collectDuplicates(player, seen, copyToSerial);
                        finish.run();
                    }, finish);
        }
    }

    private void collectDuplicates(Player player, Map<String, Integer> seen, Map<String, String> copyToSerial) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack it = player.getInventory().getItem(i);
            if (isSerialized(it) && statusOf(it) == Status.VALID) {
                seen.merge(copyIdOf(it), it.getAmount(), Integer::sum);
                copyToSerial.put(copyIdOf(it), serialOf(it));
            }
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (isSerialized(offhand) && statusOf(offhand) == Status.VALID) {
            seen.merge(copyIdOf(offhand), offhand.getAmount(), Integer::sum);
            copyToSerial.put(copyIdOf(offhand), serialOf(offhand));
        }
    }

    private void processDuplicates(Map<String, Integer> seen, Map<String, String> copyToSerial) {
        int voided = 0;
        for (Map.Entry<String, Integer> entry : seen.entrySet()) {
            if (entry.getValue() > 1) {
                String serial = copyToSerial.get(entry.getKey());
                if (serial != null && voidCopy(serial, entry.getKey())) {
                    ctx.logger().warning("Duplicate serialized copy detected and voided: "
                            + serial + " copy " + entry.getKey());
                    ctx.notifications().broadcast("vicemc.serial.admin",
                            "&cA duplicated serialized copy was detected and voided (" + serial + ").");
                    voided++;
                }
            }
        }
        if (voided > 0 && config.getBoolean("remove-on-void", true)) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.getScheduler().run(ctx.plugin(), task -> scanPlayer(player), null);
            }
        }
    }

    // --- Persistence ------------------------------------------------------

    private void load() {
        ctx.storage().moduleDataAll("serial").forEach((key, value) -> {
            if (!key.startsWith(RUN_PREFIX)) {
                return;
            }
            try {
                SerialRun run = Json.fromJson(value, SerialRun.class);
                if (run != null && run.serial != null) {
                    runs.put(run.serial, run);
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void save(SerialRun run) {
        ctx.storage().setModuleData("serial", RUN_PREFIX + run.serial, Json.toJson(run));
    }

    private String nextSerial() {
        int seq = ctx.storage().getModuleData("serial", "meta:next")
                .map(Integer::parseInt).orElse(0) + 1;
        ctx.storage().setModuleData("serial", "meta:next", String.valueOf(seq));
        String prefix = config.getString("serial-prefix", "VMC");
        return String.format("%s-%d-%04d", prefix, LocalDate.now().getYear(), seq);
    }

    private ItemStack stripSerialTags(ItemStack item) {
        ItemStack copy = item.clone();
        copy.editMeta(meta -> {
            var pdc = meta.getPersistentDataContainer();
            pdc.remove(SERIAL_KEY);
            pdc.remove(COPY_KEY);
            pdc.remove(INDEX_KEY);
            pdc.remove(INVALID_KEY);
        });
        copy.editMeta(meta -> meta.lore(stripSerialLore(meta.lore())));
        return copy;
    }

    private List<Component> stripSerialLore(List<Component> lore) {
        if (lore == null) {
            return new ArrayList<>();
        }
        List<Component> result = new ArrayList<>();
        for (Component line : lore) {
            String legacy = LegacyComponentSerializer.legacySection().serialize(line);
            boolean marker = false;
            for (String markerText : SERIAL_LORE_MARKERS) {
                if (legacy.contains(markerText)) {
                    marker = true;
                    break;
                }
            }
            if (!marker) {
                result.add(line);
            }
        }
        return result;
    }

    private List<Component> serialLore(int count, int index, boolean invalid) {
        List<Component> lore = new ArrayList<>();
        lore.add(Text.color(config.getString("lore.limited", "&d&oLimited edition")));
        if (invalid) {
            lore.add(Text.color(config.getString("lore.invalid-serial", "&cINVALID - VOIDED")));
            lore.add(Text.color(config.getString("lore.invalid-footer", "&7This item holds no value.")));
        } else {
            lore.add(Text.color(String.format(config.getString("lore.copy", "&6Serial: &e%d &8/ &e%d"),
                    Math.max(0, index), Math.max(1, count))));
        }
        return lore;
    }
}
