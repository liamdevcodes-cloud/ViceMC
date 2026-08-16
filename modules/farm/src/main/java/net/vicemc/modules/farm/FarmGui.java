package net.vicemc.modules.farm;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Farming menus in the dark modern style: the player's /farm inventory of
 * harvested crops (each carrying its farm provenance), a sell-to-government
 * confirmation that shows exactly how much the farm owners take, and an admin
 * screen for the wand, the wand crop and region management.
 */
public final class FarmGui {

    private static final int PER_PAGE = 27;

    private final ViceModuleContext ctx;
    private final FarmModule module;

    public FarmGui(ViceModuleContext ctx, FarmModule module) {
        this.ctx = ctx;
        this.module = module;
    }

    // --- Player inventory -------------------------------------------------

    public void openFarm(Player player) {
        openFarm(player, 1);
    }

    public void openFarm(Player player, int page) {
        FarmProfile profile = module.profile(player);
        List<StoredCrop> crops = profile.crops;
        List<FarmRegion> farms = module.regionsOwnedBy(player.getUniqueId());
        int pages = GuiKit.Pages.pages(crops.size(), PER_PAGE);
        int safe = Math.min(page, pages);

        var builder = ctx.gui().builder(GuiKit.title("Farm"), 6);
        GuiKit.frame(builder);
        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&6How it works",
                "&7Harvest crops in farm regions.",
                "&7Crops go here and regrow.",
                "&7Sell to the government.",
                "&7Owners take their cut.",
                "&7Click a stack to sell it."), GuiKit.NONE);
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.MAP)
                .name("&6Your farm")
                .lore("&7Farm level: &f" + profile.level,
                        "&7XP: &f" + (long) profile.xp + "&7/&f" + module.manager().xpNeeded(profile.level),
                        "&7Balance: &a" + GuiKit.fmt(module.balance(player)),
                        "&7Crops stored: &f" + totalCrops(crops))
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        if (crops.isEmpty()) {
            builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.GRAY_DYE, "&7No crops to sell",
                    "&7Harvest crops first - they",
                    "&7are stored here."), GuiKit.NONE);
        } else {
            builder.item(GuiKit.ACTION_1, GuiKit.cta(Material.GOLD_INGOT, "&aSell all to government",
                    "&7Sells every stored crop.",
                    "&7The farm owners' cut is",
                    "&7paid out of the sale."), (p, c) -> openSellConfirm(p));
        }
        if (!farms.isEmpty()) {
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.WHEAT, "&6Your farms",
                    "&7You own &f" + farms.size() + "&7 farm(s).",
                    "&7Set your margin."), (p, c) -> openMyFarms(p, 1));
        }

        List<StoredCrop> slice = GuiKit.Pages.slice(crops, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            StoredCrop stored = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, storedItem(stored),
                    (p, c) -> openStackSell(p, stored));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openFarm(p, next));
        builder.open(player);
    }

    private int totalCrops(List<StoredCrop> crops) {
        int total = 0;
        for (StoredCrop stored : crops) {
            total += stored.amount;
        }
        return total;
    }

    private ItemStack storedItem(StoredCrop stored) {
        CropType crop = module.manager().cropById(stored.crop);
        Material material = crop == null ? Material.WHEAT : crop.item;
        List<String> lore = new ArrayList<>();
        lore.add("&7Amount: &f" + stored.amount);
        lore.add("&7Value: &a" + GuiKit.fmt(crop == null ? 0 : crop.value) + "&7 each");
        if (stored.taxPercent > 0) {
            lore.add("");
            lore.add("&8" + stored.farmId + " &8- &f" + stored.farmOwnerName);
            lore.add("&8Tax: &e" + stored.taxPercent + "%");
        }
        lore.add("");
        lore.add("&7Click to sell this stack.");
        return ItemBuilder.of(material)
                .name("&6" + (crop == null ? stored.crop : crop.display()))
                .lore(lore.toArray(new String[0]))
                .build();
    }

    public void openStackSell(Player player, StoredCrop stored) {
        FarmModule.SellSummary summary = module.summarize(stored);
        CropType crop = module.manager().cropById(stored.crop);
        List<String> lore = new ArrayList<>();
        lore.add("&7Amount: &f" + stored.amount);
        lore.add("&7Total value: &f" + GuiKit.fmt(summary.total));
        if (summary.ownerCuts.isEmpty()) {
            lore.add("&7No farm taxes apply.");
        } else {
            for (Map.Entry<String, Double> e : summary.ownerCuts.entrySet()) {
                String ownerName = summary.ownerNames.getOrDefault(e.getKey(), "farm owner");
                lore.add("&7- &f" + ownerName + "&7: &f" + GuiKit.fmt(e.getValue()) + "&7 (tax)");
            }
        }
        lore.add("");
        lore.add("&7You receive: &a" + GuiKit.fmt(summary.playerShare));
        lore.add("&7Your balance: &a" + GuiKit.fmt(module.balance(player)));

        var builder = ctx.gui().builder(GuiKit.title("Sell " + (crop == null ? stored.crop : crop.display())), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("your farm"), (p, c) -> openFarm(p, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(crop == null ? Material.WHEAT : crop.item)
                .name("&6Sell this stack?")
                .lore(lore.toArray(new String[0])).build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_3, GuiKit.cta(Material.GREEN_WOOL, "&aConfirm sale",
                "&7Sell this crop stack to the government."), (p, c) -> {
            if (module.sellStack(p, stored)) {
                openFarm(p, 1);
            } else {
                ctx.notifications().warn(p, "That crop stack is already sold.");
                openFarm(p, 1);
            }
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Back to your farm."), (p, c) -> openFarm(p, 1));
        builder.open(player);
    }

    // --- Sell confirmation ------------------------------------------------

    public void openSellConfirm(Player player) {
        FarmModule.SellSummary summary = module.summarize(player);
        List<String> lore = new ArrayList<>();
        lore.add("&7Total value: &f" + GuiKit.fmt(summary.total));
        if (summary.ownerCuts.isEmpty()) {
            lore.add("&7No farm taxes apply.");
        } else {
            for (Map.Entry<String, Double> e : summary.ownerCuts.entrySet()) {
                String ownerName = summary.ownerNames.getOrDefault(e.getKey(), "farm owner");
                lore.add("&7- &f" + ownerName + "&7: &f" + GuiKit.fmt(e.getValue()) + "&7 (tax)");
            }
        }
        lore.add("");
        lore.add("&7You receive: &a" + GuiKit.fmt(summary.playerShare));
        lore.add("&7Your balance: &a" + GuiKit.fmt(module.balance(player)));

        var builder = ctx.gui().builder(GuiKit.title("Sell to government"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("your farm"), (p, c) -> openFarm(p, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.GOLD_INGOT)
                .name("&6Sell everything?")
                .lore(lore.toArray(new String[0])).build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_3, GuiKit.cta(Material.GREEN_WOOL, "&aConfirm sale",
                "&7Sell all crops to the government."), (p, c) -> {
            module.sellAll(p);
            p.closeInventory();
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Back to your farm."), (p, c) -> openFarm(p, 1));
        builder.open(player);
    }

    // --- Admin ------------------------------------------------------------

    public void openAdmin(Player player) {
        CropType wandCrop = module.wandCrop();
        var builder = ctx.gui().builder(GuiKit.title("Farm Admin"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the farm menu"), (p, c) -> openFarm(p, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.WOODEN_HOE)
                .name("&2Farm wand settings")
                .lore("&7Crop: &f" + (wandCrop == null ? "none" : wandCrop.display()),
                        "&7Regions: &f" + module.manager().all().size(),
                        "&7Left-click = corner 1,",
                        "&7Right-click = corner 2.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.WOODEN_HOE, "&aGet farm wand",
                "&7Adds the wand to your inventory."), (p, c) -> module.giveWand(p));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.WHEAT, "&6Wand crop",
                "&7The crop new regions are",
                "&7bound to."), (p, c) -> openAdminCrops(p));
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.EXPERIENCE_BOTTLE, "&6Player farm levels",
                "&7Set a player's farm level to",
                "&7unlock higher-value crops."), (p, c) -> openPlayerLevels(p, 1));
        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.REDSTONE, "&4Farm regions",
                "&7View, manage and delete regions."), (p, c) -> openAdminRegions(p, 1));
        builder.open(player);
    }

    public void openAdminCrops(Player player) {
        var builder = ctx.gui().builder(GuiKit.title("Wand crop"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the admin menu"), (p, c) -> openAdmin(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.WHEAT, "&6Choose the wand crop",
                "&7New farm regions will be",
                "&7bound to the selected crop."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        int slot = GuiKit.GRID_FIRST;
        List<CropType> crops = new ArrayList<>(module.manager().crops().values());
        int shown = Math.min(crops.size(), GuiKit.GRID_SIZE);
        for (int i = 0; i < shown; i++) {
            CropType crop = crops.get(i);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(crop.item)
                    .name("&6" + crop.display())
                    .lore("&7Value: &a" + GuiKit.fmt(crop.value) + "&7 each",
                            "&7Level: &f" + crop.level,
                            "&7Regrows in: &f" + crop.regenSeconds + "s",
                            "&aClick to select.")
                    .build(), (p, c) -> {
                module.setWandCrop(crop.id);
                ctx.notifications().msg(p, "&aFarm wand now creates &f" + crop.display() + "&a farms.");
                openAdmin(p);
            });
        }
        if (crops.size() > shown) {
            builder.item(GuiKit.GRID_FIRST + GuiKit.GRID_SIZE - 1, GuiKit.icon(Material.PAPER,
                    "&7+" + (crops.size() - shown) + " more crops",
                    "&7Set those with /farmadmin crop <id>."), GuiKit.NONE);
        }
        builder.open(player);
    }

    public void openAdminRegions(Player player, int page) {
        List<FarmRegion> regions = module.manager().all();
        int pages = GuiKit.Pages.pages(regions.size(), PER_PAGE);
        int safe = Math.min(page, pages);

        var builder = ctx.gui().builder(GuiKit.title("Farm regions"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the admin menu"), (p, c) -> openAdmin(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.REDSTONE, "&4Farm regions",
                "&7" + regions.size() + " region(s).",
                "&7Click a region for details."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        List<FarmRegion> slice = GuiKit.Pages.slice(regions, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            FarmRegion region = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, regionItem(region),
                    (p, c) -> openAdminRegion(p, region));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openAdminRegions(p, next));
        builder.open(player);
    }

    private void openAdminRegion(Player player, FarmRegion region) {
        CropType crop = module.manager().cropFor(region);
        var builder = ctx.gui().builder(GuiKit.title("Farm " + region.id), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the region list"), (p, c) -> openAdminRegions(p, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(crop == null ? Material.GRASS_BLOCK : crop.item)
                .name("&e" + region.id)
                .lore("&7Crop: &f" + (crop == null ? region.crop : crop.display()),
                        "&7Owner: &f" + (region.isPublic() ? "public"
                                : module.nameOf(UUID.fromString(region.owner))),
                        "&7Margin: &e" + Math.round(region.margin * 100) + "%",
                        "&7Bounds: &f" + region.minX + "," + region.minY + "," + region.minZ
                                + " to " + region.maxX + "," + region.maxY + "," + region.maxZ)
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.PLAYER_HEAD, "&6Owner",
                "&7Assign an owner or make the",
                "&7farm public."), (p, c) -> openAdminOwner(p, region, 1));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.GOLD_NUGGET, "&6Margin",
                "&7The share of the sale value the",
                "&7owner keeps from farmers."), (p, c) -> openMarginEditor(p, region,
                p2 -> openAdminRegion(p2, region)));
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.RED_WOOL, "&cDelete farm",
                "&7Removes the region. Existing",
                "&7crops are left in the world."), (p, c) -> openAdminDeleteConfirm(p, region));
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Back to the list."), (p, c) -> openAdminRegions(p, 1));
        builder.open(player);
    }

    private void openAdminDeleteConfirm(Player player, FarmRegion region) {
        var builder = ctx.gui().builder(GuiKit.title("Delete " + region.id), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the farm"), (p, c) -> openAdminRegion(p, region));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.RED_WOOL, "&cDelete " + region.id + "?",
                "&7This removes the farm region.",
                "&7Crops already harvested by",
                "&7players are kept."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_3, GuiKit.cta(Material.GREEN_WOOL, "&aConfirm delete",
                "&7Delete " + region.id + "."), (p, c) -> {
            module.manager().deleteRegion(region);
            ctx.notifications().msg(p, "&aDeleted farm &f" + region.id + "&a.");
            openAdminRegions(p, 1);
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Keep the farm."), (p, c) -> openAdminRegion(p, region));
        builder.open(player);
    }

    private ItemStack regionItem(FarmRegion region) {
        CropType crop = module.manager().cropFor(region);
        return ItemBuilder.of(crop == null ? Material.GRASS_BLOCK : crop.item)
                .name("&e" + region.id)
                .lore("&7Crop: &f" + (crop == null ? region.crop : crop.display()),
                        "&7Owner: &f" + (region.isPublic() ? "public"
                                : module.nameOf(UUID.fromString(region.owner))),
                        "&7Margin: &e" + Math.round(region.margin * 100) + "%",
                        "&aClick for details.")
                .build();
    }

    // --- Owner and margin (admin) ------------------------------------------

    private void openAdminOwner(Player player, FarmRegion region, int page) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int pages = GuiKit.Pages.pages(online.size(), PER_PAGE);
        int safe = Math.min(page, pages);

        var builder = ctx.gui().builder(GuiKit.title("Owner " + region.id), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the farm"), (p, c) -> openAdminRegion(p, region));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.PLAYER_HEAD)
                .name("&6Farm " + region.id)
                .lore("&7Owner: &f" + (region.isPublic() ? "public" : module.nameOf(region.owner)),
                        "&7Margin: &e" + Math.round(region.margin * 100) + "%",
                        "&7Click a player to make them",
                        "&7the owner of this farm.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_5, region.isPublic()
                ? GuiKit.icon(Material.GRAY_DYE, "&7Public farm",
                "&7No owner takes a tax.")
                : GuiKit.icon(Material.RED_WOOL, "&cMake public",
                "&7Removes the owner; no tax is",
                "&7taken from farmers."),
                (p, c) -> {
                    module.manager().setOwner(region, null);
                    ctx.notifications().msg(p, "&aFarm &f" + region.id + "&a is now public (no tax).");
                    openAdminRegion(p, region);
                });

        if (online.isEmpty()) {
            builder.item(GuiKit.GRID_FIRST, GuiKit.icon(Material.GRAY_DYE, "&7No players online",
                    "&7Assign an owner with",
                    "&e/farmadmin owner " + region.id + " <name>"), GuiKit.NONE);
        } else {
            List<Player> slice = GuiKit.Pages.slice(online, safe, PER_PAGE);
            for (int i = 0; i < slice.size(); i++) {
                Player target = slice.get(i);
                builder.item(GuiKit.GRID_FIRST + i, GuiKit.playerHead(target.getName(),
                        "&f" + target.getName(),
                        "&7Click to assign this farm.",
                        "&7Their margin: &e" + Math.round(region.margin * 100) + "%"),
                        (p, c) -> {
                            module.manager().setOwner(region, target.getUniqueId());
                            ctx.notifications().msg(p, "&aFarm &f" + region.id
                                    + "&a is now owned by &f" + target.getName() + "&a at &e"
                                    + Math.round(region.margin * 100) + "%&a.");
                            openAdminRegion(p, region);
                        });
            }
            addPaging(builder, player, safe, pages, (p, next) -> openAdminOwner(p, region, next));
        }
        builder.open(player);
    }

    /** Shared margin picker, used by admins and by farm owners themselves. */
    private void openMarginEditor(Player player, FarmRegion region, Back back) {
        int maxPct = (int) Math.round(module.manager().maxMargin() * 100);
        int current = (int) Math.round(region.margin * 100);

        var builder = ctx.gui().builder(GuiKit.title("Margin " + region.id), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the farm"), (p, c) -> back.go(p));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.GOLD_NUGGET)
                .name("&6Farm " + region.id + " margin")
                .lore("&7Owner: &f" + (region.isPublic() ? "public" : module.nameOf(region.owner)),
                        "&7Current margin: &e" + current + "%",
                        "&7Share farmers pay on sales.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        int slot = GuiKit.GRID_FIRST;
        int last = Math.min(Math.max(1, maxPct), GuiKit.GRID_SIZE);
        for (int pct = 1; pct <= last; pct++) {
            final int value = pct;
            boolean selected = pct == current;
            builder.item(slot++, ItemBuilder.of(selected ? Material.LIME_WOOL : Material.GREEN_WOOL)
                    .name(selected ? "&a" + pct + "% (current)" : "&a" + pct + "%")
                    .lore("&7Click to set the margin to " + pct + "%.")
                    .build(), (p, c) -> {
                module.manager().setMargin(region, value / 100.0);
                ctx.notifications().msg(p, "&aFarm &f" + region.id + "&a margin set to &e" + value + "%&a.");
                back.go(p);
            });
        }
        builder.open(player);
    }

    // --- Player farm levels (admin) ----------------------------------------

    public void openPlayerLevels(Player player, int page) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int pages = GuiKit.Pages.pages(online.size(), PER_PAGE);
        int safe = Math.min(page, pages);

        var builder = ctx.gui().builder(GuiKit.title("Player farm levels"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the admin menu"), (p, c) -> openAdmin(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.EXPERIENCE_BOTTLE, "&6Player farm levels",
                "&7" + online.size() + " player(s) online.",
                "&7Click a player to set their",
                "&7farm level."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        List<Player> slice = GuiKit.Pages.slice(online, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Player target = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.playerHead(target.getName(),
                    "&f" + target.getName(),
                    "&7Farm level: &f" + module.level(target.getUniqueId()),
                    "&aClick to set."),
                    (p, c) -> openSetLevel(p, target));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openPlayerLevels(p, next));
        builder.open(player);
    }

    private void openSetLevel(Player player, Player target) {
        int current = module.level(target.getUniqueId());
        var builder = ctx.gui().builder(GuiKit.title(target.getName() + " farm level"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the level list"), (p, c) -> openPlayerLevels(p, 1));
        builder.item(GuiKit.STATUS, GuiKit.playerHead(target.getName(),
                "&6" + target.getName(),
                "&7Current farm level: &f" + current,
                "&7XP resets when the level changes.",
                "&7Click a level below to set it."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        int slot = GuiKit.GRID_FIRST;
        for (int level : new int[]{1, 2, 3, 4, 5, 10, 25, 50, 100}) {
            boolean selected = level == current;
            builder.item(slot++, ItemBuilder.of(selected ? Material.LIME_WOOL : Material.GREEN_WOOL)
                    .name(selected ? "&aLevel " + level + " (current)" : "&aLevel " + level)
                    .lore("&7Click to set " + target.getName() + "'s",
                            "&7farm level to " + level + ".")
                    .build(), (p, c) -> {
                module.setLevel(target.getUniqueId(), level);
                ctx.notifications().msg(p, "&aSet &f" + target.getName()
                        + "&a's farm level to &f" + level + "&a.");
                openPlayerLevels(p, 1);
            });
        }
        builder.open(player);
    }

    // --- Your farms (player) ------------------------------------------------

    private void openMyFarms(Player player, int page) {
        List<FarmRegion> farms = module.regionsOwnedBy(player.getUniqueId());
        int pages = GuiKit.Pages.pages(farms.size(), PER_PAGE);
        int safe = Math.min(page, pages);

        var builder = ctx.gui().builder(GuiKit.title("Your farms"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the farm menu"), (p, c) -> openFarm(p, 1));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.WHEAT, "&6Your farms",
                "&7You own &f" + farms.size() + "&7 farm(s).",
                "&7Farmers pay your margin on sales."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        List<FarmRegion> slice = GuiKit.Pages.slice(farms, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            FarmRegion region = slice.get(i);
            CropType crop = module.manager().cropFor(region);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(crop == null ? Material.GRASS_BLOCK : crop.item)
                    .name("&e" + region.id)
                    .lore("&7Crop: &f" + (crop == null ? region.crop : crop.display()),
                            "&7Margin: &e" + Math.round(region.margin * 100) + "%",
                            "&aClick to change the margin.")
                    .build(), (p, c) -> openMarginEditor(p, region, p2 -> openMyFarms(p2, 1)));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openMyFarms(p, next));
        builder.open(player);
    }

    // --- Helpers ----------------------------------------------------------

    private void addPaging(GUIService.GuiBuilder builder, Player player, int page, int pages, PagingAction action) {
        if (pages <= 1) {
            return;
        }
        builder.item(GuiKit.PAGE_PREV, page > 1 ? GuiKit.prevPage(page) : GuiKit.pageGap(),
                (p, c) -> {
                    if (page > 1) {
                        action.accept(p, page - 1);
                    }
                });
        builder.item(GuiKit.PAGE_INDICATOR, GuiKit.pageIndicator(page, pages), GuiKit.NONE);
        builder.item(GuiKit.PAGE_NEXT, page < pages ? GuiKit.nextPage(page) : GuiKit.pageGap(),
                (p, c) -> {
                    if (page < pages) {
                        action.accept(p, page + 1);
                    }
                });
    }

    @FunctionalInterface
    private interface PagingAction {
        void accept(Player player, int page);
    }

    @FunctionalInterface
    private interface Back {
        void go(Player player);
    }
}
