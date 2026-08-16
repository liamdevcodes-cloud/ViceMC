package net.vicemc.modules.blooddiamond;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Blood Diamond Mine: full item loss on death inside the zone, shard mining,
 * shard crafting and $1 store-credit conversion. RMT is flagged and bannable.
 */
public final class BloodDiamondModule implements ViceModule, Listener {

    private ViceModuleContext ctx;
    private YamlConfig config;
    private BloodDiamondManager manager;
    private BloodDiamondGui gui;
    private BloodDiamondOreManager oreManager;
    private final Random random = new Random();

    private static final NamespacedKey PHONE_KEY = NamespacedKey.fromString("vicemc:phone");

    @Override
    public String id() {
        return "blooddiamond";
    }

    @Override
    public String displayName() {
        return "Vice Blood Diamond";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("blooddiamond.yml");
        this.manager = new BloodDiamondManager(ctx, config);
        this.gui = new BloodDiamondGui(ctx, this);
        this.oreManager = new BloodDiamondOreManager(ctx, config, manager, ctx.plugin());
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin());
        Bukkit.getPluginManager().registerEvents(oreManager, ctx.plugin());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("bd")
                .aliases("blooddiamond")
                .description("Blood Diamond Mine operations")
                .executes(this::bd)
                .tabulates((c, a) -> a.size() <= 1
                        ? List.of("craft", "credit", "redeem", "flag", "flags", "wand")
                        : List.of())
                .build());

        ctx.logger().info("Blood Diamond module ready.");
    }

    private void bd(CommandContext c) {
        switch (c.arg(0)) {
            case "craft" -> craft(c);
            case "credit" -> credit(c);
            case "redeem" -> redeem(c);
            case "flag" -> flag(c);
            case "flags" -> flags(c);
            case "wand" -> wand(c);
            default -> {
                if (c.isPlayer()) {
                    gui.openDashboard(c.player());
                } else {
                    c.msg("&6/bd craft [count] | credit | redeem | flag <player> <reason> | flags | wand");
                }
            }
        }
    }

    private void craft(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        craft(c.player(), Math.max(1, c.argInt(1, 1)));
    }

    /** Crafts the given number of Blood Diamonds from inventory shards. */
    public boolean craft(Player player, int count) {
        int needed = manager.shardsPerDiamond() * count;
        int have = countItems(player, BloodDiamondManager::isShard);
        if (have < needed) {
            ctx.notifications().warn(player, "You need " + needed + " shards to craft "
                    + count + " Blood Diamond(s).");
            return false;
        }
        removeItems(player, BloodDiamondManager::isShard, needed);
        ItemStack diamond = manager.bloodDiamond();
        diamond.setAmount(count);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(diamond);
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
        }
        ctx.notifications().msg(player, "&aCrafted &f" + count + " &4Blood Diamond&a.");
        return true;
    }

    private void credit(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        double credit = manager.credit(c.player().getUniqueId());
        c.msg("&6Your store credit: &f" + Text.moneyPlain(credit)
                + "&6 (non-withdrawable, for ranks and limited offers).");
    }

    private void redeem(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        redeem(c.player());
    }

    /** Redeems all Blood Diamonds in the player's inventory for store credit. */
    public int redeem(Player player) {
        int count = countItems(player, BloodDiamondManager::isBloodDiamond);
        if (count <= 0) {
            ctx.notifications().warn(player, "You have no Blood Diamonds to redeem.");
            return 0;
        }
        removeItems(player, BloodDiamondManager::isBloodDiamond, count);
        manager.addCredit(player.getUniqueId(), count);
        ctx.notifications().msg(player, "&aRedeemed &f" + count + " &4Blood Diamond&a(s) for "
                + Text.moneyPlain(count) + " store credit.");
        return count;
    }

    private void wand(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        oreManager.giveWand(c.player());
    }

    private void flag(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (!c.player().hasPermission("vicemc.blooddiamond.admin")) {
            c.error("You are not authorized.");
            return;
        }
        if (c.size() < 3) {
            c.usage("/bd flag <player> <reason>");
            return;
        }
        UUID target = c.uuidArg(1);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        String reason = c.arg(2, "real-money trading");
        manager.flagRmt(target, reason);
        c.msg("&cFlagged " + c.arg(1) + " for RMT: " + reason + ". This is a bannable offense.");
        ctx.events().publish("blooddiamond.rmt", Map.of(
                "type", "rmt", "target", c.arg(1), "reason", reason));
    }

    private void flags(CommandContext c) {
        if (!c.isPlayer() || !c.player().hasPermission("vicemc.blooddiamond.admin")) {
            c.error("You are not authorized.");
            return;
        }
        Map<String, String> flags = manager.rmtFlags();
        if (flags.isEmpty()) {
            c.msg("&7No RMT flags.");
            return;
        }
        c.msg("&4RMT flags:");
        flags.forEach((uuid, reason) -> c.msg("  &7- &f" + nameOf(UUID.fromString(uuid)) + "&7: " + reason));
    }

    // --- Mine death rules -------------------------------------------------

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        String tag = config.getString("mine.region-tag", "blooddiamond");
        if (!ctx.regions().isInside(player.getLocation(), tag)) {
            return;
        }
        if (event.getKeepInventory()) {
            dropEverything(player);
            event.setKeepInventory(false);
            event.setKeepLevel(false);
        } else {
            event.setKeepLevel(false);
        }
        ctx.notifications().warn(player, "You died in the Blood Diamond Mine - your items were lost.");
    }

    private void dropEverything(Player player) {
        Location loc = player.getLocation();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && !ItemBuilder.hasTag(item, PHONE_KEY, "true")) {
                player.getWorld().dropItemNaturally(loc, item);
            }
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null && !ItemBuilder.hasTag(item, PHONE_KEY, "true")) {
                player.getWorld().dropItemNaturally(loc, item);
            }
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && !ItemBuilder.hasTag(offhand, PHONE_KEY, "true")) {
            player.getWorld().dropItemNaturally(loc, offhand);
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);
    }

    // --- Shard mining -----------------------------------------------------

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        String tag = config.getString("mine.region-tag", "blooddiamond");
        if (!ctx.regions().isInside(event.getBlock().getLocation(), tag)) {
            return;
        }
        List<String> materials = config.getStringList("mine.materials");
        if (!materials.isEmpty() && !materials.contains(event.getBlock().getType().name())) {
            return;
        }
        double chance = config.getDouble("mine.shard-chance", 0.15);
        if (random.nextDouble() > chance) {
            return;
        }
        ItemStack shard = manager.shard();
        Map<Integer, ItemStack> leftover = event.getPlayer().getInventory().addItem(shard);
        if (!leftover.isEmpty()) {
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(),
                    leftover.values().iterator().next());
        }
        ctx.notifications().msg(event.getPlayer(), "&4You found a Blood Diamond Shard!");
    }

    // --- Item helpers -----------------------------------------------------

    public BloodDiamondManager manager() {
        return manager;
    }

    public void giveWand(Player player) {
        oreManager.giveWand(player);
    }

    public int shardsInInventory(Player player) {
        return countItems(player, BloodDiamondManager::isShard);
    }

    public int diamondsInInventory(Player player) {
        return countItems(player, BloodDiamondManager::isBloodDiamond);
    }

    private int countItems(Player player, java.util.function.Predicate<ItemStack> match) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (match.test(item)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private void removeItems(Player player, java.util.function.Predicate<ItemStack> match, int amount) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack item = contents[i];
            if (match.test(item)) {
                int take = Math.min(amount, item.getAmount());
                item.setAmount(item.getAmount() - take);
                amount -= take;
                if (item.getAmount() <= 0) {
                    player.getInventory().setItem(i, null);
                }
            }
        }
    }

    private String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }
}
