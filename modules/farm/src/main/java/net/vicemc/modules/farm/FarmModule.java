package net.vicemc.modules.farm;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Farming: admin-created regenerative crop regions with per-region crop types,
 * farming levels that gate the more expensive crops, /farm inventory that
 * stores harvested crops with their provenance (which farm, whose farm, tax
 * %), and selling to the government where the farm owner takes their margin.
 */
public final class FarmModule implements ViceModule {

    private ViceModuleContext ctx;
    private YamlConfig config;
    private FarmManager manager;
    private FarmGui gui;
    private FarmListener listener;
    private FarmPresenceListener presence;

    @Override
    public String id() {
        return "farm";
    }

    @Override
    public String displayName() {
        return "Vice Farm";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("farming.yml");
        this.manager = new FarmManager(ctx, config);
        this.gui = new FarmGui(ctx, this);
        this.listener = new FarmListener(this);
        this.presence = new FarmPresenceListener(this);

        context.buildProtection().claim(block -> {
            FarmRegion region = manager.at(block);
            if (region == null) {
                return false;
            }
            CropType crop = manager.cropFor(region);
            return crop != null && block.getType() == crop.block;
        });

        Bukkit.getPluginManager().registerEvents(listener, ctx.plugin());
        Bukkit.getPluginManager().registerEvents(presence, ctx.plugin());
        registerCommands();

        ctx.logger().info("Farm module ready.");
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            listener.shutdown();
        }
    }

    // --- Commands ---------------------------------------------------------

    private void registerCommands() {
        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("farm")
                .aliases("farming")
                .description("Your farm inventory, crops and earnings")
                .executes(c -> {
                    if (c.isPlayer()) {
                        gui.openFarm(c.player());
                    } else {
                        c.msg("&6/farm opens your farm inventory in-game.");
                    }
                })
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("farmadmin")
                .aliases("fa")
                .permission("vicemc.farm.admin")
                .description("Set up farm regions, the wand, ownership and margins")
                .executes(this::farmadmin)
                .tabulates(this::tabs)
                .build());
    }

    private void farmadmin(CommandContext c) {
        if (!c.isPlayer()) {
            c.msg("&6/farmadmin requires the in-game menu. Open it on a player.");
            return;
        }
        Player admin = c.player();
        if (!admin.hasPermission("vicemc.farm.admin")) {
            c.error("You are not authorized.");
            return;
        }
        switch (c.arg(0)) {
            case "wand" -> giveWand(admin);
            case "crop" -> crop(admin, c.arg(1));
            case "info" -> info(admin, c.arg(1));
            case "remove" -> remove(admin, c.arg(1));
            case "owner" -> owner(admin, c.arg(1), c.arg(2));
            case "margin" -> margin(admin, c.arg(1), c.argDouble(2, -1));
            case "level" -> level(admin, c.arg(1), c.argInt(2, -1));
            case "admin" -> gui.openAdmin(admin);
            default -> {
                if (c.size() == 0) {
                    gui.openAdmin(admin);
                } else {
                    c.usage("/farmadmin wand | crop [id] | info <id> | remove <id>"
                            + " | owner <id> <player> | margin <id> <0.01-0.10> | level <player> <level> | admin");
                }
            }
        }
    }

    private void crop(Player admin, String value) {
        CropType current = manager.wandCrop();
        if (value == null || value.isEmpty()) {
            ctx.notifications().msg(admin, "&6Current wand crop: &f"
                    + (current == null ? "none" : current.display())
                    + "&6. Change it with &e/farmadmin crop " + manager.cropIds()
                    + "&6.");
            return;
        }
        CropType crop = manager.cropById(value);
        if (crop == null) {
            ctx.notifications().warn(admin, "Unknown crop. Available: " + manager.cropIds());
            return;
        }
        manager.setWandCrop(crop.id);
        ctx.notifications().msg(admin, "&aFarm wand now creates &f" + crop.display()
                + "&a farms worth &f" + Text.moneyPlain(crop.value) + "&a per crop.");
    }

    private void info(Player admin, String value) {
        FarmRegion region = manager.byId(value);
        if (region == null) {
            ctx.notifications().warn(admin, "Farm not found.");
            return;
        }
        CropType crop = manager.cropFor(region);
        ctx.notifications().msg(admin, "&6" + region.id + "&7 (" + (crop == null ? region.crop : crop.display())
                + "&7) - Owner: &f" + (region.isPublic() ? "public" : nameOf(region.owner))
                + "&7 - Margin: &e" + Math.round(region.margin * 100) + "%"
                + "&7 - " + region.minX + "," + region.minY + "," + region.minZ
                + " to " + region.maxX + "," + region.maxY + "," + region.maxZ);
    }

    private void remove(Player admin, String value) {
        FarmRegion region = manager.byId(value);
        if (region == null) {
            ctx.notifications().warn(admin, "Farm not found.");
            return;
        }
        manager.deleteRegion(region);
        ctx.notifications().msg(admin, "&aDeleted farm &f" + region.id + "&a.");
    }

    private void owner(Player admin, String id, String name) {
        FarmRegion region = manager.byId(id);
        if (region == null) {
            ctx.notifications().warn(admin, "Farm not found.");
            return;
        }
        if (name == null || name.isEmpty()) {
            manager.setOwner(region, null);
            ctx.notifications().msg(admin, "&aFarm &f" + region.id + "&a is now public (no tax).");
            return;
        }
        UUID uuid = manager.uuidByName(name);
        if (uuid == null) {
            ctx.notifications().warn(admin, "Player '" + name + "' not found.");
            return;
        }
        manager.setOwner(region, uuid);
        ctx.notifications().msg(admin, "&aFarm &f" + region.id + "&a is now owned by &f"
                + manager.nameOf(uuid) + "&a at &e" + Math.round(region.margin * 100) + "%&a.");
    }

    private void margin(Player admin, String id, double value) {
        FarmRegion region = manager.byId(id);
        if (region == null) {
            ctx.notifications().warn(admin, "Farm not found.");
            return;
        }
        if (value < 0) {
            cusage(admin, "/farmadmin margin <id> <0.01-0.10>");
            return;
        }
        manager.setMargin(region, value);
        ctx.notifications().msg(admin, "&aFarm &f" + region.id + "&a margin set to &e"
                + Math.round(region.margin * 100) + "%&a.");
    }

    private void level(Player admin, String name, int value) {
        if (value < 1) {
            cusage(admin, "/farmadmin level <player> <level>");
            return;
        }
        UUID uuid = manager.uuidByName(name);
        if (uuid == null) {
            ctx.notifications().warn(admin, "Player '" + name + "' not found.");
            return;
        }
        manager.setLevel(uuid, value);
        ctx.notifications().msg(admin, "&aSet &f" + manager.nameOf(uuid) + "&a's farm level to &f"
                + value + "&a.");
    }

    private List<String> tabs(CommandContext c, List<String> args) {
        if (args.size() <= 1) {
            return List.of("wand", "crop", "info", "remove", "owner", "margin", "level", "admin");
        }
        String sub = args.get(0);
        if (sub.equals("crop") && args.size() == 2) {
            return manager.cropIds();
        }
        if ((sub.equals("info") || sub.equals("remove") || sub.equals("owner")
                || sub.equals("margin")) && args.size() == 2) {
            return manager.regionIds();
        }
        if ((sub.equals("owner") || sub.equals("level")) && args.size() == 3) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }

    // --- Facade for the GUI ----------------------------------------------

    public ViceModuleContext context() {
        return ctx;
    }

    public YamlConfig config() {
        return config;
    }

    public FarmManager manager() {
        return manager;
    }

    public FarmProfile profile(Player player) {
        return manager.profile(player.getUniqueId());
    }

    public List<FarmRegion> regionsOwnedBy(UUID owner) {
        return manager.regionsOwnedBy(owner);
    }

    public int level(UUID uuid) {
        return manager.level(uuid);
    }

    public void setLevel(UUID uuid, int level) {
        manager.setLevel(uuid, level);
    }

    public CropType wandCrop() {
        return manager.wandCrop();
    }

    public void setWandCrop(String id) {
        manager.setWandCrop(id);
    }

    public double balance(Player player) {
        return ctx.economy().balance(player.getUniqueId());
    }

    public String nameOf(String uuidString) {
        return manager.nameOf(uuidString);
    }

    public String nameOf(UUID uuid) {
        return manager.nameOf(uuid);
    }

    public void giveWand(Player player) {
        CropType crop = manager.wandCrop();
        ItemStack wand = ItemBuilder.of(Material.WOODEN_HOE)
                .name("&2Farm Wand")
                .lore("&7Left-click: corner 1",
                        "&7Right-click: corner 2",
                        "&7Creates a &f" + (crop == null ? "?" : crop.display()) + "&7 farm.",
                        "&7Crop: &e/farmadmin crop <id>")
                .tag(FarmListener.wandKey(), "true")
                .build();
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(wand);
        if (!leftover.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover.values().iterator().next());
        }
        ctx.notifications().msg(player, "&aHere is your farm wand. Select two corners to create a farm.");
    }

    // --- Selling ----------------------------------------------------------

    /** Computes the sale value and the tax cut per farm owner for a player's crops. */
    public SellSummary summarize(Player player) {
        SellSummary summary = new SellSummary();
        FarmProfile profile = profile(player);
        for (StoredCrop stored : profile.crops) {
            CropType crop = manager.cropById(stored.crop);
            if (crop == null) {
                continue;
            }
            double value = crop.value * stored.amount;
            summary.total += value;
            if (stored.taxPercent > 0 && !stored.farmOwnerId.isEmpty()) {
                double cut = value * stored.taxPercent / 100.0;
                summary.ownerCuts.merge(stored.farmOwnerId, cut, Double::sum);
                summary.ownerNames.putIfAbsent(stored.farmOwnerId, stored.farmOwnerName);
            }
        }
        for (double cut : summary.ownerCuts.values()) {
            summary.taxes += cut;
        }
        summary.playerShare = summary.total - summary.taxes;
        return summary;
    }

    /** Sells every stored crop to the government and pays out the farm owners. */
    public void sellAll(Player player) {
        FarmProfile profile = profile(player);
        if (profile.crops.isEmpty()) {
            ctx.notifications().warn(player, "You have no crops to sell.");
            return;
        }
        SellSummary summary = summarize(player);
        if (summary.total <= 0) {
            ctx.notifications().warn(player, "Your crops have no sellable value.");
            return;
        }
        if (summary.playerShare > 0) {
            ctx.economy().deposit(player.getUniqueId(), summary.playerShare, "farm sell");
        }
        for (Map.Entry<String, Double> entry : summary.ownerCuts.entrySet()) {
            double cut = entry.getValue();
            if (cut <= 0 || entry.getKey().equals(player.getUniqueId().toString())) {
                continue;
            }
            ctx.economy().deposit(UUID.fromString(entry.getKey()), cut, "farm tax income");
            Player owner = Bukkit.getPlayer(entry.getKey());
            if (owner != null) {
                ctx.notifications().msg(owner, "&aYour farm taxes earned you &f"
                        + Text.moneyPlain(cut) + "&a from &f" + player.getName() + "&a.");
            }
        }
        profile.crops.clear();
        manager.saveProfile(player.getUniqueId(), profile);
        ctx.notifications().msg(player, "&aSold your crops to the government for &f"
                + Text.moneyPlain(summary.total) + "&a (&f" + Text.moneyPlain(summary.playerShare)
                + "&a to you, &f" + Text.moneyPlain(summary.taxes) + "&a in farm taxes).");
        ctx.events().publish("farm.sold", Map.of(
                "player", player.getName(), "total", summary.total,
                "playerShare", summary.playerShare, "taxes", summary.taxes));
    }

    /** Computes the value and tax cut of a single stored crop stack. */
    public SellSummary summarize(StoredCrop stored) {
        SellSummary summary = new SellSummary();
        CropType crop = manager.cropById(stored.crop);
        if (crop == null) {
            return summary;
        }
        double value = crop.value * stored.amount;
        summary.total = value;
        if (stored.taxPercent > 0 && !stored.farmOwnerId.isEmpty()) {
            double cut = value * stored.taxPercent / 100.0;
            summary.ownerCuts.put(stored.farmOwnerId, cut);
            summary.ownerNames.put(stored.farmOwnerId, stored.farmOwnerName);
            summary.taxes = cut;
        }
        summary.playerShare = summary.total - summary.taxes;
        return summary;
    }

    /** Sells one stored stack (from the /farm GUI) and pays out its farm owner. */
    public boolean sellStack(Player player, StoredCrop wanted) {
        FarmProfile profile = profile(player);
        StoredCrop stored = null;
        for (StoredCrop candidate : profile.crops) {
            if (candidate.crop.equalsIgnoreCase(wanted.crop)
                    && candidate.farmId.equals(wanted.farmId)
                    && candidate.farmOwnerId.equals(wanted.farmOwnerId)
                    && candidate.taxPercent == wanted.taxPercent) {
                stored = candidate;
                break;
            }
        }
        if (stored == null) {
            return false;
        }
        profile.crops.remove(stored);
        SellSummary summary = summarize(stored);
        if (summary.playerShare > 0) {
            ctx.economy().deposit(player.getUniqueId(), summary.playerShare, "farm sell");
        }
        for (Map.Entry<String, Double> entry : summary.ownerCuts.entrySet()) {
            double cut = entry.getValue();
            if (cut <= 0 || entry.getKey().equals(player.getUniqueId().toString())) {
                continue;
            }
            ctx.economy().deposit(UUID.fromString(entry.getKey()), cut, "farm tax income");
            Player owner = Bukkit.getPlayer(entry.getKey());
            if (owner != null) {
                ctx.notifications().msg(owner, "&aYour farm taxes earned you &f"
                        + Text.moneyPlain(cut) + "&a from &f" + player.getName() + "&a.");
            }
        }
        manager.saveProfile(player.getUniqueId(), profile);
        CropType crop = manager.cropById(stored.crop);
        String name = crop == null ? stored.crop : crop.display();
        ctx.notifications().msg(player, "&aSold &f" + stored.amount + "x " + name + "&a for &f"
                + Text.moneyPlain(summary.total) + "&a (&f" + Text.moneyPlain(summary.playerShare)
                + "&a to you, &f" + Text.moneyPlain(summary.taxes) + "&a in farm taxes).");
        return true;
    }

    /** Result of pricing a player's stored crops, used by the sell confirmation. */
    public static final class SellSummary {
        public double total;
        public double taxes;
        public double playerShare;
        public Map<String, Double> ownerCuts = new LinkedHashMap<>();
        public Map<String, String> ownerNames = new LinkedHashMap<>();
    }

    private void cusage(Player player, String usage) {
        ctx.notifications().msg(player, "&eUsage: " + usage);
    }

    private List<String> cropIds() {
        return new ArrayList<>(manager.cropIds());
    }
}
