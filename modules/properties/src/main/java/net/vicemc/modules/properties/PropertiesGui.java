package net.vicemc.modules.properties;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Real estate menus in the dark modern style: a dashboard with your limits, a
 * market grouped by plot type, plot management (members, sale, rent), buy and
 * rent confirmations (with an interior preview so buyers cannot be scammed)
 * and an admin screen for the wand, default value and permits.
 */
public final class PropertiesGui {

    private static final int PER_PAGE = 27;

    private final ViceModuleContext ctx;
    private final PropertiesModule module;

    public PropertiesGui(ViceModuleContext ctx, PropertiesModule module) {
        this.ctx = ctx;
        this.module = module;
    }

    // --- Dashboard --------------------------------------------------------

    public void openDashboard(Player player) {
        UUID me = player.getUniqueId();
        boolean admin = player.hasPermission("vicemc.properties.admin");

        var builder = ctx.gui().builder(GuiKit.title("Real Estate"), 6);
        GuiKit.frame(builder);
        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&6How it works",
                "&7Buy a plot on the market, furnish",
                "&7it and only your own furniture can",
                "&7be modified. Sell it back empty, or",
                "&7with a &6real estate permit&7 resell",
                "&7it with the interior included."), GuiKit.NONE);
        List<Plot> owned = module.manager().ownedBy(me);
        List<Plot> memberships = module.manager().memberOf(me);
        List<String> statusLore = new ArrayList<>(List.of(limitsLore(me)));
        statusLore.add("");
        statusLore.add("&7Balance: &a" + GuiKit.fmt(module.balance(player)));
        statusLore.add("&7Permit: " + (module.hasPermit(me) ? "&aheld" : "&7none"));
        statusLore.add("&7Member of: &f" + memberships.size() + "&7 plot(s)");
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.MAP)
                .name("&6Your real estate")
                .lore(statusLore.toArray(new String[0])).build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.CHEST, "&6Market",
                "&7Browse plots by type and buy",
                "&7or rent them."), (p, c) -> openMarketType(p));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.OAK_PLANKS, "&6My plots",
                "&7Manage your properties: members,",
                "&7teleport, sale and rent."), (p, c) -> openMyPlots(p, 1));
        builder.item(GuiKit.ACTION_3, module.hasPermit(me)
                ? GuiKit.icon(Material.LIME_DYE, "&aReal estate permit",
                "&7Higher limits and interior",
                "&7resales unlocked.")
                : GuiKit.icon(Material.GRAY_DYE, "&7No real estate permit",
                "&7Study the real estate route at",
                "&7university to unlock higher limits",
                "&7and selling plots with their interior."), GuiKit.NONE);
        if (admin) {
            builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.REDSTONE, "&4Admin",
                    "&7Wand, default value, permits",
                    "&7and plot management."), (p, c) -> openAdmin(p));
        }
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.CLOCK, "&6My rentals",
                "&7Plots you rent: teleport,",
                "&7renew or abandon them."), (p, c) -> openMyRentals(p, 1));

        int shown = Math.min(owned.size(), PER_PAGE);
        for (int i = 0; i < shown; i++) {
            Plot plot = owned.get(i);
            builder.item(GuiKit.GRID_FIRST + i, ownedItem(plot), (p, c) -> openManage(p, plot));
        }
        int slot = GuiKit.GRID_FIRST + shown;
        for (Plot plot : memberships) {
            if (slot >= GuiKit.GRID_FIRST + PER_PAGE) {
                break;
            }
            builder.item(slot++, memberItem(plot), (p, c) -> openMemberPlot(p, plot));
        }
        if (owned.size() > shown) {
            builder.item(GuiKit.GRID_FIRST + PER_PAGE - 1, GuiKit.icon(Material.PAPER,
                    "&7+" + (owned.size() - shown) + " more plots",
                    "&7See them all in 'My plots'."), (p, c) -> openMyPlots(p, 1));
        }
        builder.open(player);
    }

    private String[] limitsLore(UUID me) {
        String[] lines = new String[PlotType.values().length];
        int i = 0;
        for (PlotType type : PlotType.values()) {
            int owned = module.ownedCountByType(me, type);
            int limit = module.limit(type, me);
            lines[i++] = "&7" + type.display() + ": &f" + owned + "&7/&f" + limit
                    + (owned >= limit ? " &c(full)" : "");
        }
        return lines;
    }

    private static String[] concat(String[] a, String... b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private ItemStack ownedItem(Plot plot) {
        return ItemBuilder.of(plot.type().icon())
                .name("&e" + plot.serial + " &7" + plot.type().display())
                .lore(concat(statusLore(plot),
                        "&7Owner: &f" + (plot.owner == null ? "unowned" : module.nameOf(plot.owner)),
                        "&aClick to manage."))
                .build();
    }

    private String[] statusLore(Plot plot) {
        if (plot.forSale) {
            return new String[]{
                    "&7Value: &f" + GuiKit.fmt(plot.price),
                    "&6For sale: &f" + GuiKit.fmt(plot.salePrice)
                            + (plot.sellWithInterior ? " &a(interior included)" : " &7(empty)")};
        }
        if (plot.isRented()) {
            return new String[]{
                    "&7Value: &f" + GuiKit.fmt(plot.price),
                    "&bRented to &f" + module.nameOf(plot.tenant)
                            + "&b until " + module.date(plot.rentUntil)};
        }
        if (plot.forRent) {
            return new String[]{
                    "&7Value: &f" + GuiKit.fmt(plot.price),
                    "&bFor rent: &f" + GuiKit.fmt(plot.rentPrice)
                            + "&b per " + plot.rentDays + " day(s)"};
        }
        return new String[]{
                "&7Value: &f" + GuiKit.fmt(plot.price),
                "&7Furniture: &f" + plot.placed.size() + " block(s)"};
    }

    // --- My plots ---------------------------------------------------------

    public void openMyPlots(Player player, int page) {
        List<Plot> owned = module.manager().ownedBy(player.getUniqueId());
        int pages = GuiKit.Pages.pages(owned.size(), PER_PAGE);
        int safe = Math.min(page, pages);

        var builder = ctx.gui().builder(GuiKit.title("My plots"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the real estate menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.OAK_PLANKS, "&6My plots",
                "&7You own &f" + owned.size() + "&7 plot(s)."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        List<Plot> slice = GuiKit.Pages.slice(owned, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Plot plot = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, ownedItem(plot), (p, c) -> openManage(p, plot));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openMyPlots(p, next));
        builder.open(player);
    }

    // --- My rentals -------------------------------------------------------

    public void openMyRentals(Player player, int page) {
        List<Plot> rentals = module.manager().rentedBy(player.getUniqueId());
        int pages = GuiKit.Pages.pages(rentals.size(), PER_PAGE);
        int safe = Math.min(page, pages);

        var builder = ctx.gui().builder(GuiKit.title("My rentals"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the real estate menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.CLOCK, "&6My rentals",
                "&7You rent &f" + rentals.size() + "&7 plot(s)."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        List<Plot> slice = GuiKit.Pages.slice(rentals, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Plot plot = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, rentalItem(plot), (p, c) -> openRentalActions(p, plot));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openMyRentals(p, next));
        builder.open(player);
    }

    private ItemStack rentalItem(Plot plot) {
        long daysLeft = Math.max(0, (plot.rentUntil - System.currentTimeMillis()) / 86400000L);
        return ItemBuilder.of(plot.type().icon())
                .name("&e" + plot.serial + " &7" + plot.type().display())
                .lore("&7Landlord: &f" + module.nameOf(plot.owner),
                        "&7Rent: &f" + GuiKit.fmt(plot.rentPrice) + "&7 per " + plot.rentDays + " day(s)",
                        "&7Until: &f" + module.date(plot.rentUntil),
                        "&7Days left: &f" + daysLeft,
                        "&aClick for options.")
                .build();
    }

    private void openRentalActions(Player player, Plot plot) {
        long daysLeft = Math.max(0, (plot.rentUntil - System.currentTimeMillis()) / 86400000L);
        var builder = ctx.gui().builder(GuiKit.title("Rent " + plot.serial), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("your rentals"), (p, c) -> openMyRentals(p, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(plot.type().icon())
                .name("&e" + plot.serial)
                .lore("&7Type: &f" + plot.type().display(),
                        "&7Landlord: &f" + module.nameOf(plot.owner),
                        "&7Rent: &f" + GuiKit.fmt(plot.rentPrice) + "&7 per " + plot.rentDays + " day(s)",
                        "&7Until: &f" + module.date(plot.rentUntil),
                        "&7Days left: &f" + daysLeft)
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.ENDER_PEARL, "&aTeleport",
                "&7Go to the plot."), (p, c) -> {
            p.closeInventory();
            module.teleportTo(p, plot);
        });
        builder.item(GuiKit.ACTION_3, GuiKit.cta(Material.GREEN_WOOL, "&aRenew",
                "&7Extend by another " + plot.rentDays + " day(s)",
                "&7for &f" + GuiKit.fmt(plot.rentPrice) + "&7."), (p, c) -> {
            if (module.renewRental(p, plot)) {
                openRentalActions(p, plot);
            }
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.RED_WOOL, "&cAbandon",
                "&7End the rental now. Your",
                "&7furniture is removed."), (p, c) -> openAbandonConfirm(p, plot));
        builder.open(player);
    }

    private void openAbandonConfirm(Player player, Plot plot) {
        var builder = ctx.gui().builder(GuiKit.title("Abandon " + plot.serial), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the rental"), (p, c) -> openRentalActions(p, plot));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.RED_WOOL, "&cAbandon " + plot.serial + "?",
                "&7End the rental now. Your",
                "&7placed furniture is removed.",
                "&7You are not refunded."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_3, GuiKit.cta(Material.GREEN_WOOL, "&aConfirm abandon",
                "&7End the rental."), (p, c) -> {
            if (module.abandonRental(p, plot)) {
                openMyRentals(p, 1);
            }
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Keep the rental."), (p, c) -> openRentalActions(p, plot));
        builder.open(player);
    }

    // --- Member plots -----------------------------------------------------

    private ItemStack memberItem(Plot plot) {
        return ItemBuilder.of(plot.type().icon())
                .name("&e" + plot.serial + " &7" + plot.type().display())
                .lore("&7Owner: &f" + (plot.owner == null ? "unowned" : module.nameOf(plot.owner)),
                        "&bYou are a member.",
                        "&aClick to teleport, toggle the",
                        "&aborder or leave the plot.")
                .build();
    }

    private void openMemberPlot(Player player, Plot plot) {
        var builder = ctx.gui().builder(GuiKit.title(plot.serial), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the real estate menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, ItemBuilder.of(plot.type().icon())
                .name("&e" + plot.serial)
                .lore("&7Owner: &f" + module.nameOf(plot.owner),
                        "&bYou are a member here.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.ENDER_PEARL, "&aTeleport",
                "&7Go to the plot."), (p, c) -> {
            p.closeInventory();
            module.teleportTo(p, plot);
        });
        builder.item(GuiKit.ACTION_2, borderToggleItem(player, plot),
                (p, c) -> {
                    module.toggleBorder(p, plot);
                    openMemberPlot(p, plot);
                });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.RED_WOOL, "&cLeave plot",
                "&7Remove yourself as a member."), (p, c) -> openLeaveConfirm(p, plot));
        builder.open(player);
    }

    private void openLeaveConfirm(Player player, Plot plot) {
        var builder = ctx.gui().builder(GuiKit.title("Leave " + plot.serial), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the real estate menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.RED_WOOL, "&cLeave " + plot.serial + "?",
                "&7You will no longer be able to",
                "&7build in this plot."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_3, GuiKit.cta(Material.GREEN_WOOL, "&aConfirm leave",
                "&7Leave the plot."), (p, c) -> {
            if (module.leavePlot(p, plot)) {
                openDashboard(p);
            }
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Stay in the plot."), (p, c) -> openDashboard(p));
        builder.open(player);
    }

    private ItemStack borderToggleItem(Player player, Plot plot) {
        boolean on = module.borderEnabled(player.getUniqueId());
        return GuiKit.icon(on ? Material.GLOWSTONE_DUST : Material.GRAY_DYE,
                on ? "&aPlot border: on" : "&7Plot border: off",
                "&7Shows the plot's edges with",
                "&7golden particles while you stand",
                "&7inside it.");
    }

    // --- Market -----------------------------------------------------------

    public void openMarketType(Player player) {
        var builder = ctx.gui().builder(GuiKit.title("Market"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the real estate menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.CHEST, "&6Market",
                "&7Choose a plot type.",
                "&7Balance: &a" + GuiKit.fmt(module.balance(player))), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        int slot = GuiKit.ACTION_1;
        for (PlotType type : PlotType.values()) {
            int available = module.manager().market(type).size() + module.manager().forRent(type).size();
            List<String> lore = new ArrayList<>();
            lore.add("&7" + available + " plot(s) available.");
            String licenseLine = module.licenseLine(player, type);
            if (licenseLine != null) {
                lore.add(licenseLine);
            }
            lore.add("&7Limit: &f" + module.limit(type, player.getUniqueId()) + "&7 owned.");
            lore.add("&aClick to browse.");
            builder.item(slot, ItemBuilder.of(type.icon())
                    .name("&6" + type.display() + "s")
                    .lore(lore.toArray(new String[0]))
                    .build(), (p, c) -> openMarket(p, type, 1));
            slot += 1;
        }
        builder.open(player);
    }

    public void openMarket(Player player, PlotType type, int page) {
        List<Plot> market = new ArrayList<>(module.manager().market(type));
        market.addAll(module.manager().forRent(type));
        market.sort((p1, p2) -> p1.serial.compareTo(p2.serial));
        int pages = GuiKit.Pages.pages(market.size(), PER_PAGE);
        int safe = Math.min(page, pages);

        var builder = ctx.gui().builder(GuiKit.title(type.display() + " market"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the market"), (p, c) -> openMarketType(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(type.icon(), "&6" + type.display() + " market",
                "&7" + market.size() + " plot(s).",
                "&7Balance: &a" + GuiKit.fmt(module.balance(player))), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        List<Plot> slice = GuiKit.Pages.slice(market, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Plot plot = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, marketItem(player, plot),
                    (p, c) -> {
                        if (plot.forRent && !plot.isRented()) {
                            openRentConfirm(p, plot);
                        } else {
                            openBuyConfirm(p, plot);
                        }
                    });
        }
        addPaging(builder, player, safe, pages, (p, next) -> openMarket(p, type, next));
        builder.open(player);
    }

    private ItemStack marketItem(Player player, Plot plot) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Serial: &e" + plot.serial);
        String licenseLine = module.licenseLine(player, plot.type());
        if (licenseLine != null) {
            lore.add(licenseLine);
        }
        if (plot.forRent) {
            lore.add("&bFor rent: &f" + GuiKit.fmt(plot.rentPrice) + "&b per " + plot.rentDays + " day(s)");
            lore.add("&7By: &f" + module.nameOf(plot.owner));
            lore.add("&aClick to rent.");
        } else if (plot.owner == null) {
            lore.add("&7Price: &f" + GuiKit.fmt(plot.price));
            lore.add("&aClick to buy (empty plot).");
        } else {
            lore.add("&6Resale: &f" + GuiKit.fmt(plot.salePrice)
                    + (plot.sellWithInterior ? " &a(interior included)" : " &7(empty)"));
            lore.add("&7By: &f" + module.nameOf(plot.owner));
            lore.add("&aClick to buy.");
        }
        return ItemBuilder.of(plot.type().icon()).name("&e" + plot.serial)
                .lore(lore.toArray(new String[0])).build();
    }

    // --- Buy / rent confirmations ------------------------------------------

    public void openBuyConfirm(Player player, Plot plot) {
        double cost = module.purchasePrice(plot);
        boolean licensed = module.hasLicense(player, plot.type());
        List<String> lore = new ArrayList<>();
        lore.add("&7Serial: &e" + plot.serial);
        lore.add("&7Type: &f" + plot.type().display());
        String licenseLine = module.licenseLine(player, plot.type());
        if (licenseLine != null) {
            lore.add(licenseLine);
        }
        if (plot.owner != null) {
            lore.add("&7Seller: &f" + module.nameOf(plot.owner));
        }
        lore.add("&7Price: &f" + GuiKit.fmt(cost));
        lore.add("&7Your balance: &a" + GuiKit.fmt(module.balance(player)));
        if (plot.forSale && plot.sellWithInterior && !plot.interiorManifest.isEmpty()) {
            lore.add("");
            lore.add("&6&lIncluded interior");
            for (String line : plot.interiorManifest.split("\n")) {
                lore.add(line);
            }
            lore.add("");
            lore.add("&7The interior is locked until the sale ends.");
        } else {
            lore.add("&7This plot is sold empty - any existing");
            lore.add("&7furniture is removed before delivery.");
        }

        var builder = ctx.gui().builder(GuiKit.title("Buy " + plot.serial), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the market"), (p, c) -> openMarket(p, plot.type(), 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(plot.type().icon())
                .name("&e" + plot.serial)
                .lore(lore.toArray(new String[0])).build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        if (licensed) {
            builder.item(GuiKit.ACTION_3, GuiKit.cta(Material.GREEN_WOOL, "&aConfirm purchase",
                    "&7Pay &f" + GuiKit.fmt(cost) + "&7."), (p, c) -> {
                if (module.buy(p, plot)) {
                    p.closeInventory();
                } else {
                    openBuyConfirm(p, plot);
                }
            });
        } else {
            builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.RED_WOOL, "&cLocked",
                    "&7You need the required license",
                    "&7first. Get one at the government",
                    "&7building, then come back."), GuiKit.NONE);
        }
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Back to the market."), (p, c) -> openMarket(p, plot.type(), 1));
        builder.open(player);
    }

    public void openRentConfirm(Player player, Plot plot) {
        double cost = plot.rentPrice;
        long days = plot.rentDays;
        var builder = ctx.gui().builder(GuiKit.title("Rent " + plot.serial), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the market"), (p, c) -> openMarket(p, plot.type(), 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(plot.type().icon())
                .name("&e" + plot.serial)
                .lore("&7Rent: &b" + GuiKit.fmt(cost) + "&7 per " + days + " day(s)",
                        "&7Landlord: &f" + module.nameOf(plot.owner),
                        "&7Your balance: &a" + GuiKit.fmt(module.balance(player)),
                        "",
                        "&7You can build while you rent.",
                        "&7When the rental ends your placed",
                        "&7furniture is removed.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_3, GuiKit.cta(Material.GREEN_WOOL, "&aRent now",
                "&7Pay &f" + GuiKit.fmt(cost) + "&7 for " + days + " day(s)."), (p, c) -> {
            if (module.rent(p, plot)) {
                p.closeInventory();
            } else {
                openRentConfirm(p, plot);
            }
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Back to the market."), (p, c) -> openMarket(p, plot.type(), 1));
        builder.open(player);
    }

    // --- Manage plot ------------------------------------------------------

    public void openManage(Player player, Plot plot) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Serial: &e" + plot.serial);
        lore.addAll(List.of(statusLore(plot)));
        lore.add("&7Location: &f" + plot.minX + ", " + plot.minY + ", " + plot.minZ);
        lore.add("&7Members: &f" + plot.members.size());

        var builder = ctx.gui().builder(GuiKit.title("Manage " + plot.serial), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("your plots"), (p, c) -> openMyPlots(p, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(plot.type().icon())
                .name("&e" + plot.serial)
                .lore(lore.toArray(new String[0])).build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.ENDER_PEARL, "&aTeleport",
                "&7Go to the plot."), (p, c) -> {
            p.closeInventory();
            module.teleportTo(p, plot);
        });

        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.PLAYER_HEAD, "&6Members",
                "&7Add or remove players who",
                "&7can build here."), (p, c) -> openMembers(p, plot, 1));

        builder.item(GuiKit.GRID_FIRST, borderToggleItem(player, plot),
                (p, c) -> {
                    module.toggleBorder(p, plot);
                    openManage(p, plot);
                });

        if (plot.forSale) {
            builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.BARRIER, "&cCancel sale",
                    "&7Remove from the market."), (p, c) -> {
                if (module.unlistForSale(p, plot)) {
                    openManage(p, plot);
                }
            });
        } else if (plot.isRented()) {
            builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.GRAY_DYE, "&7Rented",
                    "&7Cancel the rental first, then",
                    "&7this plot can be listed for sale."), GuiKit.NONE);
        } else {
            builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.GOLD_INGOT, "&6Put up for sale",
                    "&7Set a price. With a permit you",
                    "&7can include the interior."), (p, c) -> promptSalePrice(p, plot));
        }

        if (plot.isRented()) {
            builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.BARRIER, "&cCancel rental",
                    "&7End the rental and clear",
                    "&7the tenant's furniture."), (p, c) -> {
                if (module.cancelRent(p, plot)) {
                    openManage(p, plot);
                }
            });
        } else if (plot.forRent) {
            builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.BARRIER, "&cCancel listing",
                    "&7Remove from the rental market."), (p, c) -> {
                if (module.unlistForRent(p, plot)) {
                    openManage(p, plot);
                }
            });
        } else if (plot.forSale) {
            builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.GOLD_INGOT, "&6Put up for rent",
                    "&7Unlist from sale first, then",
                    "&7rent the plot to tenants."), GuiKit.NONE);
        } else {
            builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.CLOCK, "&6Put up for rent",
                    "&7Rent it out for a price per",
                    "&7period (days)."), (p, c) -> promptRentPrice(p, plot));
        }

        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.RED_DYE, "&cSell back",
                "&7Return to the market empty",
                "&7for &f" + GuiKit.fmt(module.resaleValue(plot)) + "&7.",
                "&7All placed furniture is removed."), (p, c) -> {
            if (module.sellBack(p, plot)) {
                openDashboard(p);
            }
        });
        builder.open(player);
    }

    private void promptSalePrice(Player player, Plot plot) {
        module.prompts().prompt(player,
                "&eType the sale price for &f" + plot.serial + "&e, or &ccancel&e.",
                (p, input) -> {
                    if (input.equalsIgnoreCase("cancel")) {
                        openManage(p, plot);
                        return;
                    }
                    try {
                        double price = Double.parseDouble(input.trim());
                        if (price < 0) {
                            throw new NumberFormatException();
                        }
                        openSellSetup(p, plot, price, false);
                    } catch (NumberFormatException ex) {
                        ctx.notifications().warn(p, "Invalid price.");
                        promptSalePrice(p, plot);
                    }
                });
    }

    private void promptRentPrice(Player player, Plot plot) {
        module.prompts().prompt(player,
                "&eType the rent price per period for &f" + plot.serial + "&e, or &ccancel&e.",
                (p, input) -> {
                    if (input.equalsIgnoreCase("cancel")) {
                        openManage(p, plot);
                        return;
                    }
                    try {
                        double price = Double.parseDouble(input.trim());
                        if (price < 0) {
                            throw new NumberFormatException();
                        }
                        promptRentDays(p, plot, price);
                    } catch (NumberFormatException ex) {
                        ctx.notifications().warn(p, "Invalid price.");
                        promptRentPrice(p, plot);
                    }
                });
    }

    private void promptRentDays(Player player, Plot plot, double price) {
        module.prompts().prompt(player,
                "&eHow many days per period (e.g. 7)? Type a number or &ccancel&e.",
                (p, input) -> {
                    if (input.equalsIgnoreCase("cancel")) {
                        openManage(p, plot);
                        return;
                    }
                    try {
                        long days = Long.parseLong(input.trim());
                        if (days < 1) {
                            throw new NumberFormatException();
                        }
                        openRentSetup(p, plot, price, days);
                    } catch (NumberFormatException ex) {
                        ctx.notifications().warn(p, "Invalid number of days.");
                        promptRentDays(p, plot, price);
                    }
                });
    }

    private void openSellSetup(Player player, Plot plot, double price, boolean interior) {
        boolean canInterior = module.hasPermit(player.getUniqueId());
        List<String> lore = new ArrayList<>();
        lore.add("&7Price: &f" + GuiKit.fmt(price));
        lore.add("&7Mode: " + (interior ? "&aInterior included" : "&7Empty plot"));
        if (interior) {
            lore.add("");
            lore.add("&6&lInterior being sold");
            for (String line : module.manager().buildManifest(plot).split("\n")) {
                lore.add(line);
            }
            lore.add("");
            lore.add("&7The plot is locked until the sale");
            lore.add("&7completes so nothing can change.");
        }

        var builder = ctx.gui().builder(GuiKit.title("Sell " + plot.serial), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the plot"), (p, c) -> openManage(p, plot));
        builder.item(GuiKit.STATUS, ItemBuilder.of(plot.type().icon())
                .name("&e" + plot.serial)
                .lore(lore.toArray(new String[0])).build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        if (canInterior) {
            builder.item(GuiKit.ACTION_2, GuiKit.icon(interior ? Material.GREEN_WOOL : Material.GRAY_WOOL,
                    interior ? "&aInterior included" : "&7Sold empty",
                    "&7Click to switch between selling",
                    "&7with the interior or as an empty plot."),
                    (p, c) -> openSellSetup(p, plot, price, !interior));
        } else {
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.GRAY_DYE, "&7Empty plot only",
                    "&7The &6real estate permit&7 unlocks",
                    "&7selling with the interior included."), GuiKit.NONE);
        }

        builder.item(GuiKit.ACTION_4, GuiKit.cta(Material.GREEN_WOOL, "&aConfirm listing",
                "&7List &f" + plot.serial + "&7 at &f" + GuiKit.fmt(price) + "&7."),
                (p, c) -> {
                    if (module.listForSale(p, plot, price, interior)) {
                        openManage(p, plot);
                    } else {
                        openSellSetup(p, plot, price, interior);
                    }
                });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Keep the plot."), (p, c) -> openManage(p, plot));
        builder.open(player);
    }

    private void openRentSetup(Player player, Plot plot, double price, long days) {
        var builder = ctx.gui().builder(GuiKit.title("Rent " + plot.serial), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the plot"), (p, c) -> openManage(p, plot));
        builder.item(GuiKit.STATUS, ItemBuilder.of(plot.type().icon())
                .name("&e" + plot.serial)
                .lore("&bRent: &f" + GuiKit.fmt(price) + "&b per " + days + " day(s)",
                        "&7Tenants can build in the plot.",
                        "&7Their furniture is removed when",
                        "&7the rental expires.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_4, GuiKit.cta(Material.GREEN_WOOL, "&aConfirm listing",
                "&7Rent &f" + plot.serial + "&7 at &f" + GuiKit.fmt(price)
                        + "&7 per " + days + " day(s)."), (p, c) -> {
            if (module.listForRent(p, plot, price, days)) {
                openManage(p, plot);
            } else {
                openRentSetup(p, plot, price, days);
            }
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Keep the plot."), (p, c) -> openManage(p, plot));
        builder.open(player);
    }

    // --- Members ----------------------------------------------------------

    public void openMembers(Player player, Plot plot, int page) {
        List<String> members = new ArrayList<>(plot.members);
        int pages = GuiKit.Pages.pages(members.size(), PER_PAGE);
        int safe = Math.min(page, pages);

        var builder = ctx.gui().builder(GuiKit.title("Members - " + plot.serial), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the plot"), (p, c) -> openManage(p, plot));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.PLAYER_HEAD, "&6Members",
                "&7" + members.size() + " member(s).",
                "&7Click a member to remove them."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.NAME_TAG, "&aAdd member",
                "&7Type the player's name in chat."), (p, c) -> {
            module.prompts().prompt(p,
                    "&eType the name of the player to add, or &ccancel&e.",
                    (pl, input) -> {
                        if (input.equalsIgnoreCase("cancel")) {
                            openMembers(pl, plot, safe);
                            return;
                        }
                        if (module.addMember(pl, plot, input.trim())) {
                            openMembers(pl, plot, safe);
                        }
                    });
        });

        List<String> slice = GuiKit.Pages.slice(members, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            String memberId = slice.get(i);
            String name = module.nameOf(memberId);
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.playerHead(name,
                    "&f" + name,
                    "&7Click to remove from the plot."),
                    (p, c) -> {
                        if (module.removeMember(p, plot, name)) {
                            openMembers(p, plot, safe);
                        }
                    });
        }
        addPaging(builder, player, safe, pages, (p, next) -> openMembers(p, plot, next));
        builder.open(player);
    }

    // --- Admin ------------------------------------------------------------

    public void openAdmin(Player player) {
        var builder = ctx.gui().builder(GuiKit.title("Plot Admin"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the real estate menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.STICK)
                .name("&6Plot wand settings")
                .lore("&7Type: &f" + module.manager().wandType().display(),
                        "&7Default value: &f" + GuiKit.fmt(module.manager().wandValue()),
                        "&7Cuboid: left/right-click corners.",
                        "&7Polygon: click vertices, right-click",
                        "&7to close & set height.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.STICK, "&aGet cuboid wand",
                "&7Left-click: corner 1",
                "&7Right-click: corner 2 to create."),
                (p, c) -> module.giveWand(p));

        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.GOLDEN_AXE, "&bGet polygon wand",
                "&7Click blocks to draw the",
                "&7shape, right-click to set height."),
                (p, c) -> module.givePolyWand(p));

        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.ARROW, "&6Wand type: "
                        + module.manager().wandType().display(),
                        "&7Click to cycle the plot type",
                        "&7new plots are created with."),
                (p, c) -> {
                    PlotType current = module.manager().wandType();
                    PlotType next = PlotType.values()[(current.ordinal() + 1) % PlotType.values().length];
                    module.setWandType(next);
                    openAdmin(p);
                });

        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.GOLD_INGOT, "&6Set default value",
                "&7The value each new plot gets,",
                "&7so you never type it per plot."),
                (p, c) -> module.prompts().prompt(p,
                        "&eType the new default value, or &ccancel&e.",
                        (pl, input) -> {
                            if (input.equalsIgnoreCase("cancel")) {
                                openAdmin(pl);
                                return;
                            }
                            try {
                                double value = Double.parseDouble(input.trim());
                                if (value <= 0) {
                                    throw new NumberFormatException();
                                }
                                module.setWandValue(value);
                                ctx.notifications().msg(pl, "&aDefault value set to &f"
                                        + GuiKit.fmt(value) + "&a.");
                                openAdmin(pl);
                            } catch (NumberFormatException ex) {
                                ctx.notifications().warn(pl, "Invalid value.");
                                openAdmin(pl);
                            }
                        }));

        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.NAME_TAG, "&6Grant permit",
                "&7Toggle a player's real estate",
                "&7permit (university stand-in)."),
                (p, c) -> module.prompts().prompt(p,
                        "&eType the player's name to toggle their permit, or &ccancel&e.",
                        (pl, input) -> {
                            if (input.equalsIgnoreCase("cancel")) {
                                openAdmin(pl);
                                return;
                            }
                            String id = module.playerIdByName(input.trim());
                            if (id == null) {
                                ctx.notifications().warn(pl, "Player not found.");
                                openAdmin(pl);
                                return;
                            }
                            UUID uuid = UUID.fromString(id);
                            module.grantPermit(uuid, !module.hasPermit(uuid));
                            ctx.notifications().msg(pl, "&aPermit for &f" + input.trim()
                                    + "&a is now &f" + (module.hasPermit(uuid) ? "ON" : "OFF") + "&a.");
                            openAdmin(pl);
                        }));

        builder.item(GuiKit.GRID_FIRST, GuiKit.icon(Material.REDSTONE, "&4Plot list",
                "&7View and delete plots."),
                (p, c) -> openAdminTypeList(p));

        builder.open(player);
    }

    private void openAdminTypeList(Player player) {
        var builder = ctx.gui().builder(GuiKit.title("Admin - plot types"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the admin menu"), (p, c) -> openAdmin(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.REDSTONE, "&4Choose a plot type",
                "&7Shows every plot of that type."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        int slot = GuiKit.ACTION_1;
        for (PlotType type : PlotType.values()) {
            int total = module.manager().byType(type).size();
            builder.item(slot, ItemBuilder.of(type.icon())
                    .name("&6" + type.display() + "s")
                    .lore("&7" + total + " plot(s).", "&aClick to view.")
                    .build(), (p, c) -> openAdminList(p, type, 1));
            slot += 1;
        }
        builder.open(player);
    }

    public void openAdminList(Player player, PlotType type, int page) {
        List<Plot> plots = module.manager().byType(type);
        int pages = GuiKit.Pages.pages(plots.size(), PER_PAGE);
        int safe = Math.min(page, pages);

        var builder = ctx.gui().builder(GuiKit.title("Plots - " + type.display()), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the type list"), (p, c) -> openAdminTypeList(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(type.icon(), "&6" + type.display() + " plots",
                "&7" + plots.size() + " total."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        List<Plot> slice = GuiKit.Pages.slice(plots, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Plot plot = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(type.icon())
                    .name("&e" + plot.serial)
                    .lore("&7Value: &f" + GuiKit.fmt(plot.price),
                            "&7Owner: &f" + (plot.owner == null ? "unowned" : module.nameOf(plot.owner)),
                            "&7Furniture: &f" + plot.placed.size() + " block(s)",
                            "&aClick for details.")
                    .build(), (p, c) -> openAdminPlot(p, plot));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openAdminList(p, type, next));
        builder.open(player);
    }

    private void openAdminPlot(Player player, Plot plot) {
        var builder = ctx.gui().builder(GuiKit.title("Plot " + plot.serial), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the plot list"), (p, c) -> openAdminList(p, plot.type(), 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(plot.type().icon())
                .name("&e" + plot.serial)
                .lore("&7Type: &f" + plot.type().display(),
                        "&7Value: &f" + GuiKit.fmt(plot.price),
                        "&7Owner: &f" + (plot.owner == null ? "unowned" : module.nameOf(plot.owner)),
                        "&7Furniture: &f" + plot.placed.size() + " block(s)",
                        "&7Bounds: &f" + plot.minX + "," + plot.minY + "," + plot.minZ
                                + " to " + plot.maxX + "," + plot.maxY + "," + plot.maxZ)
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.RED_WOOL, "&cDelete plot",
                "&7Removes the plot and clears",
                "&7all furniture inside."), (p, c) -> openAdminDeleteConfirm(p, plot));
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Back to the list."), (p, c) -> openAdminList(p, plot.type(), 1));
        builder.open(player);
    }

    private void openAdminDeleteConfirm(Player player, Plot plot) {
        var builder = ctx.gui().builder(GuiKit.title("Delete " + plot.serial), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the plot"), (p, c) -> openAdminPlot(p, plot));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.RED_WOOL, "&cDelete " + plot.serial + "?",
                "&7This removes the plot and",
                "&7clears all furniture. This cannot",
                "&7be undone."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_3, GuiKit.cta(Material.GREEN_WOOL, "&aConfirm delete",
                "&7Delete " + plot.serial + "."), (p, c) -> {
            module.adminRemovePlot(plot);
            ctx.notifications().msg(p, "&aDeleted &f" + plot.serial + "&a.");
            openAdminList(p, plot.type(), 1);
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Keep the plot."), (p, c) -> openAdminPlot(p, plot));
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
}
