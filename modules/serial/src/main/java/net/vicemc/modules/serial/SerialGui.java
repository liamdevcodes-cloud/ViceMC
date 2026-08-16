package net.vicemc.modules.serial;

import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Staff GUIs for the serial system.
 *
 * Console: deposit a piece, choose a copy count, create the run, then browse
 * existing runs. Distribution: a chest holding one slot per unclaimed copy;
 * clicking a copy hands it out (it is now in game), copies can never be placed
 * back, and both runs and individual copies can be voided via confirmation.
 */
public final class SerialGui implements Listener {

    private enum Kind { CONSOLE, DISTRIBUTION, MANAGE_COPIES, VOID_RUN_CONFIRM, VOID_COPY_CONFIRM, RESTORE_COPY_CONFIRM }

    private static final int DEPOSIT = 4;
    private static final int CREATE = 8;
    private static final int COUNT_FIRST = 9;
    private static final int LIST_FIRST = 18;
    private static final int MANAGE = 51;
    private static final int INFO = 52;
    private static final int VOID_RUN = 53;
    private static final int YES = 0;
    private static final int NO = 8;

    private final SerialManager manager;
    private final JavaPlugin plugin;
    private final Map<UUID, State> open = new HashMap<>();

    public SerialGui(SerialManager manager, JavaPlugin plugin) {
        this.manager = manager;
        this.plugin = plugin;
    }

    public void openConsole(Player player) {
        List<Integer> counts = manager.counts();
        Inventory inv = Bukkit.createInventory(null, 54, Text.color("&6ViceSerial Console"));
        fill(inv);
        inv.setItem(0, ItemBuilder.of(Material.BOOK).name("&6&lHow it works")
                .lore("&7Put a custom item in the &edeposit",
                        "&eslot&7, pick how many copies",
                        "&7should exist, then hit &aCreate&7.",
                        "&7Copies are handed out from the",
                        "&7distribution chest.").build());
        inv.setItem(3, accent());
        inv.setItem(5, accent());
        inv.setItem(DEPOSIT, null);
        inv.setItem(7, selectionItem(0));
        inv.setItem(CREATE, ItemBuilder.of(Material.ANVIL).name("&a&lCreate Serial Run")
                .lore("&7Pick a &ecopy count&7 first.",
                        "&7Requires an item in the &edeposit slot&7.",
                        "&aCreates a new limited serial number.").glow().build());
        for (int i = 0; i < counts.size() && i < 8; i++) {
            inv.setItem(COUNT_FIRST + i, countItem(counts.get(i), false));
        }
        List<SerialRun> visible = manager.all();
        State state = new State(Kind.CONSOLE, inv, null, 0, null, visible);
        for (int i = 0; i < visible.size() && i < 36; i++) {
            inv.setItem(LIST_FIRST + i, runButton(visible.get(i)));
        }
        open(player, state);
    }

    public void openDistribution(Player player, SerialRun run) {
        Inventory inv = Bukkit.createInventory(null, 54, Text.color("&6Serial " + run.serial));
        fill(inv);
        inv.setItem(INFO, ItemBuilder.of(Material.MAP).name("&6&lDistribution chest")
                .lore("&7Click a copy to hand it out.",
                        "&7It is then &ain game&7 and cannot be",
                        "&7returned. Only &cvoiding&7 removes it.",
                        "&8Use the barrel to manage/void copies").build());
        inv.setItem(VOID_RUN, ItemBuilder.of(Material.TNT).name("&4&lVoid Serial")
                .lore("&7Invalidates every copy of " + run.serial,
                        "&4This cannot be undone.").build());
        inv.setItem(MANAGE, ItemBuilder.of(Material.BARREL).name("&6&lManage Copies")
                .lore("&7Browse every copy of this serial,",
                        "&7including ones already handed out.",
                        "&eClick a copy to void it (INVALID).",
                        "&aClick a voided copy to restore it.").build());
        State state = new State(Kind.DISTRIBUTION, inv, run, 0, null, List.of());
        populateCopies(state);
        open(player, state);
    }

    public void openVoidRunConfirm(Player player, SerialRun run) {
        Inventory inv = Bukkit.createInventory(null, 9, Text.color("&6Void " + run.serial + "?"));
        fill(inv);
        inv.setItem(YES, ItemBuilder.of(Material.GREEN_WOOL).name("&a&lConfirm - void " + run.serial)
                .lore("&7All copies become &cINVALID&7 and worthless.",
                        "&4This cannot be undone.").glow().build());
        inv.setItem(NO, ItemBuilder.of(Material.RED_WOOL).name("&c&lCancel").build());
        inv.setItem(4, runButton(run));
        open(player, new State(Kind.VOID_RUN_CONFIRM, inv, run, 0, null, List.of()));
    }

    public void openVoidCopyConfirm(Player player, SerialRun run, SerialCopy copy) {
        Inventory inv = Bukkit.createInventory(null, 9, Text.color("&6Void copy #" + copy.index + "?"));
        fill(inv);
        inv.setItem(YES, ItemBuilder.of(Material.GREEN_WOOL).name("&a&lConfirm - void copy #" + copy.index)
                .lore("&7That single copy becomes &cINVALID&7.",
                        "&4This cannot be undone.").glow().build());
        inv.setItem(NO, ItemBuilder.of(Material.RED_WOOL).name("&c&lCancel").build());
        ItemStack template = manager.templateOf(run);
        if (template != null) {
            inv.setItem(4, manager.stampCopy(template, run, copy));
        }
        open(player, new State(Kind.VOID_COPY_CONFIRM, inv, run, 0, copy.id, List.of()));
    }

    public void openRestoreCopyConfirm(Player player, SerialRun run, SerialCopy copy) {
        Inventory inv = Bukkit.createInventory(null, 9, Text.color("&6Restore copy #" + copy.index + "?"));
        fill(inv);
        inv.setItem(YES, ItemBuilder.of(Material.GREEN_WOOL).name("&a&lConfirm - restore copy #" + copy.index)
                .lore("&7This copy becomes &avalid&7 again",
                        "&7and returns to the distribution chest.").glow().build());
        inv.setItem(NO, ItemBuilder.of(Material.RED_WOOL).name("&c&lCancel").build());
        ItemStack template = manager.templateOf(run);
        if (template != null) {
            inv.setItem(4, manager.stampCopy(template, run, copy));
        }
        open(player, new State(Kind.RESTORE_COPY_CONFIRM, inv, run, 0, copy.id, List.of()));
    }

    public void openManageCopies(Player player, SerialRun run) {
        Inventory inv = Bukkit.createInventory(null, 54, Text.color("&6Copies of " + run.serial));
        fill(inv);
        for (int i = 1; i <= run.count && i <= 54; i++) {
            SerialCopy copy = run.copyByIndex(i);
            if (copy == null) {
                continue;
            }
            ItemBuilder builder;
            if (copy.voided) {
                builder = ItemBuilder.of(Material.RED_WOOL).name("&4&lCopy #" + i + " - VOIDED")
                        .lore("&7Worthless and out of circulation.",
                                "&aClick to restore it back to the server.");
            } else if (copy.claimed) {
                builder = ItemBuilder.of(Material.YELLOW_DYE).name("&6&lCopy #" + i + " - In game")
                        .lore("&7Claimed by: &f" + nameOf(copy.claimedBy),
                                "&7Claimed: &f" + formatTime(copy.claimedAt),
                                "&eClick to void this copy.");
            } else {
                builder = ItemBuilder.of(Material.GREEN_DYE).name("&a&lCopy #" + i + " - In chest")
                        .lore("&7Still available in the",
                                "&7distribution chest.");
            }
            inv.setItem(i - 1, builder.build());
        }
        open(player, new State(Kind.MANAGE_COPIES, inv, run, 0, null, List.of()));
    }

    public void refresh(String serial) {
        for (State state : new ArrayList<>(open.values())) {
            if (state.kind == Kind.DISTRIBUTION && state.run != null
                    && state.run.serial.equals(serial)) {
                populateCopies(state);
            }
        }
    }

    public void closeAll() {
        for (Player player : new ArrayList<>(open.keySet()).stream()
                .map(Bukkit::getPlayer).filter(p -> p != null).toList()) {
            player.closeInventory();
        }
        open.clear();
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        State state = open.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        if (event.isShiftClick()) {
            event.setCancelled(true);
            onShift(player, event, state);
            return;
        }
        if (event.getClickedInventory() == null) {
            event.setCancelled(true);
            return;
        }
        if (event.getClickedInventory().equals(state.inv)) {
            onGuiClick(player, event, state);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && open.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        State state = open.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        if (state.kind == Kind.CONSOLE) {
            ItemStack deposited = state.inv.getItem(DEPOSIT);
            if (deposited != null && deposited.getType() != Material.AIR) {
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(deposited);
                if (!leftover.isEmpty()) {
                    player.getWorld().dropItemNaturally(player.getLocation(),
                            leftover.values().iterator().next());
                }
            }
        }
    }

    private void onGuiClick(Player player, InventoryClickEvent event, State state) {
        int slot = event.getSlot();
        switch (state.kind) {
            case CONSOLE -> {
                if (slot == DEPOSIT) {
                    return;
                }
                event.setCancelled(true);
                if (slot == CREATE) {
                    createFromConsole(player, state);
                } else if (slot >= COUNT_FIRST && slot < COUNT_FIRST + 8) {
                    List<Integer> counts = manager.counts();
                    int index = slot - COUNT_FIRST;
                    if (index < counts.size()) {
                        state.count = counts.get(index);
                        for (int i = 0; i < counts.size() && i < 8; i++) {
                            state.inv.setItem(COUNT_FIRST + i, countItem(counts.get(i), counts.get(i) == state.count));
                        }
                        state.inv.setItem(7, selectionItem(state.count));
                        player.updateInventory();
                    }
                } else if (slot >= LIST_FIRST && slot < 54) {
                    int index = slot - LIST_FIRST;
                    if (index < state.visibleRuns.size()) {
                        openDistribution(player, state.visibleRuns.get(index));
                    }
                }
            }
            case DISTRIBUTION -> {
                event.setCancelled(true);
                if (slot == MANAGE && state.run != null) {
                    openManageCopies(player, state.run);
                    return;
                }
                if (slot == VOID_RUN && state.run != null) {
                    openVoidRunConfirm(player, state.run);
                    return;
                }
                ItemStack item = state.inv.getItem(slot);
                if (item == null || !manager.isSerialized(item) || state.run == null) {
                    return;
                }
                String copyId = manager.copyIdOf(item);
                SerialCopy copy = copyId == null ? null : state.run.copyById(copyId);
                if (copy == null || copy.claimed || copy.voided) {
                    return;
                }
                giveCopy(player, state, copy, slot);
            }
            case MANAGE_COPIES -> {
                event.setCancelled(true);
                if (state.run == null || slot < 0 || slot >= state.run.count) {
                    return;
                }
                SerialCopy copy = state.run.copyByIndex(slot + 1);
                if (copy == null) {
                    return;
                }
                if (copy.voided) {
                    openRestoreCopyConfirm(player, state.run, copy);
                } else {
                    openVoidCopyConfirm(player, state.run, copy);
                }
            }
            case VOID_RUN_CONFIRM, VOID_COPY_CONFIRM, RESTORE_COPY_CONFIRM -> {
                event.setCancelled(true);
                if (slot == NO) {
                    player.closeInventory();
                } else if (slot == YES) {
                    confirmVoid(player, state);
                }
            }
        }
    }

    private void onShift(Player player, InventoryClickEvent event, State state) {
        int slot = event.getSlot();
        switch (state.kind) {
            case CONSOLE -> {
                int index = slot - LIST_FIRST;
                if (slot >= LIST_FIRST && index < state.visibleRuns.size()) {
                    openVoidRunConfirm(player, state.visibleRuns.get(index));
                }
            }
            case DISTRIBUTION -> {
                ItemStack item = state.inv.getItem(slot);
                if (item != null && manager.isSerialized(item) && state.run != null) {
                    String copyId = manager.copyIdOf(item);
                    SerialCopy copy = copyId == null ? null : state.run.copyById(copyId);
                    if (copy != null && !copy.voided) {
                        openVoidCopyConfirm(player, state.run, copy);
                    }
                }
            }
            default -> {
            }
        }
    }

    private void createFromConsole(Player player, State state) {
        ItemStack template = state.inv.getItem(DEPOSIT);
        if (template == null || template.getType() == Material.AIR) {
            player.sendMessage(Text.color("&cPlace the piece in the deposit slot first."));
            return;
        }
        if (state.count <= 0) {
            player.sendMessage(Text.color("&cPick a copy count first."));
            return;
        }
        SerialRun run = manager.createRun(template, state.count);
        state.inv.setItem(DEPOSIT, null);
        player.closeInventory();
        player.sendMessage(Text.color("&aCreated serial &f" + run.serial
                + "&a with &f" + state.count + "&a copies."));
        openDistribution(player, run);
    }

    private void giveCopy(Player player, State state, SerialCopy copy, int slot) {
        ItemStack give = state.inv.getItem(slot).clone();
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(give);
        if (!leftover.isEmpty()) {
            player.sendMessage(Text.color("&cYour inventory is full - the copy stays in the chest."));
            return;
        }
        manager.claim(state.run, copy, player);
        state.inv.setItem(slot, null);
        player.sendMessage(Text.color("&aCopy #" + copy.index + " of &f" + state.run.serial
                + "&a is now in game."));
    }

    private void confirmVoid(Player player, State state) {
        if (state.kind == Kind.VOID_RUN_CONFIRM && state.run != null) {
            SerialRun run = manager.voidRun(state.run.serial);
            if (run != null) {
                player.sendMessage(Text.color("&cSerial &f" + run.serial
                        + "&c voided - all copies are now invalid."));
                if (manager.removeOnVoid()) {
                    manager.scanOnlineAsync(player, removed -> {
                        if (removed > 0) {
                            player.sendMessage(Text.color("&7Removed " + removed
                                    + " invalidated item(s) from online inventories."));
                        }
                    });
                }
            }
        } else if (state.kind == Kind.VOID_COPY_CONFIRM && state.run != null && state.copyId != null) {
            if (manager.voidCopy(state.run.serial, state.copyId)) {
                player.sendMessage(Text.color("&cVoided a copy of &f" + state.run.serial
                        + "&c - it is now invalid."));
                if (manager.removeOnVoid()) {
                    manager.scanOnlineAsync(player, removed -> {
                        if (removed > 0) {
                            player.sendMessage(Text.color("&7Removed " + removed
                                    + " invalidated item(s) from online inventories."));
                        }
                    });
                }
            }
        } else if (state.kind == Kind.RESTORE_COPY_CONFIRM && state.run != null && state.copyId != null) {
            if (manager.restoreCopy(state.run.serial, state.copyId)) {
                player.sendMessage(Text.color("&aRestored a copy of &f" + state.run.serial
                        + "&a - it is back in the distribution chest."));
            }
        }
        refresh(state.run == null ? null : state.run.serial);
        player.closeInventory();
    }

    private void populateCopies(State state) {
        if (state.run == null) {
            return;
        }
        for (int i = 0; i < MANAGE; i++) {
            state.inv.setItem(i, null);
        }
        ItemStack template = manager.templateOf(state.run);
        if (template == null) {
            return;
        }
        List<SerialCopy> available = state.run.copies.values().stream()
                .filter(c -> !c.claimed && !c.voided)
                .sorted(java.util.Comparator.comparingInt(c -> c.index))
                .toList();
        int slot = 0;
        for (SerialCopy copy : available) {
            if (slot >= MANAGE) {
                break;
            }
            state.inv.setItem(slot++, manager.stampCopy(template, state.run, copy));
        }
    }

    private void open(Player player, State state) {
        if (open.containsKey(player.getUniqueId())) {
            player.closeInventory();
        }
        player.openInventory(state.inv);
        open.put(player.getUniqueId(), state);
    }

    private void fill(Inventory inv) {
        ItemStack filler = ItemBuilder.of(Material.WHITE_STAINED_GLASS_PANE).name(" ").build();
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
    }

    private ItemStack accent() {
        return ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE).name("&6&lDeposit slot").build();
    }

    private ItemStack selectionItem(int count) {
        if (count > 0) {
            return ItemBuilder.of(Material.LIME_DYE).name("&a&lSelected: &f&l" + count + " copies").build();
        }
        return ItemBuilder.of(Material.PAPER).name("&e&lNo count selected")
                .lore("&7Click a number below.").build();
    }

    private ItemStack countItem(int value, boolean selected) {
        return ItemBuilder.of(selected ? Material.LIME_DYE : Material.PAPER)
                .name((selected ? "&a&l" : "&e") + value + " copies")
                .lore(selected ? "&a&lSelected" : "&7Click to choose")
                .build();
    }

    private ItemStack runButton(SerialRun run) {
        ItemStack template = manager.templateOf(run);
        Material material = template == null ? Material.BOOK : template.getType();
        String name = (run.voided ? "&4&l" : "&6&l") + run.serial;
        int claimed = 0;
        for (SerialCopy copy : run.copies.values()) {
            if (copy.claimed && !copy.voided) {
                claimed++;
            }
        }
        ItemBuilder builder = ItemBuilder.of(material)
                .name(name)
                .lore("&7Copies left in chest: &a" + run.remaining() + "&8/&a" + run.count,
                        "&7Claimed and in game: &b" + claimed);
        if (run.voided) {
            builder.lore("&4&lVOIDED&r &7- all copies invalid");
        } else {
            builder.lore("&eClick:&7 open distribution chest",
                    "&eShift-click:&7 void serial");
        }
        return builder.build();
    }

    private String nameOf(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return "unknown";
        }
        try {
            Player online = Bukkit.getPlayer(UUID.fromString(uuid));
            if (online != null) {
                return online.getName();
            }
            String name = Bukkit.getOfflinePlayer(UUID.fromString(uuid)).getName();
            return name == null ? uuid.substring(0, 8) : name;
        } catch (IllegalArgumentException ex) {
            return uuid;
        }
    }

    private String formatTime(long millis) {
        if (millis <= 0) {
            return "unknown";
        }
        long seconds = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (seconds < 60) {
            return seconds + "s ago";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h ago";
        }
        return (hours / 24) + "d ago";
    }

    private static final class State {
        final Kind kind;
        final Inventory inv;
        final SerialRun run;
        final String copyId;
        final List<SerialRun> visibleRuns;
        int count;

        State(Kind kind, Inventory inv, SerialRun run, int count, String copyId, List<SerialRun> visibleRuns) {
            this.kind = kind;
            this.inv = inv;
            this.run = run;
            this.count = count;
            this.copyId = copyId;
            this.visibleRuns = visibleRuns;
        }
    }
}
