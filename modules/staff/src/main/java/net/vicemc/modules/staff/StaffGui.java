package net.vicemc.modules.staff;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.model.Region;
import net.vicemc.api.model.Transaction;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.core.ViceCore;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The staff panel. View tier can inspect every module's raw data, economy
 * totals, players and regions. Act tier can run teleport and moderation
 * actions. Senior tier (OP-only) can additionally run money and license
 * actions. Every action is audited, rate limited and blocked against
 * self/other staff. Audit tier can read the audit trail.
 */
public final class StaffGui {

    private static final int PER_PAGE = 27;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    private final ViceModuleContext ctx;
    private final StaffModule module;
    private final StaffPrompts prompts;
    private final Map<UUID, MoneyDraft> moneyDrafts = new ConcurrentHashMap<>();
    private final Map<UUID, PunishDraft> punishDrafts = new ConcurrentHashMap<>();
    private final Map<UUID, String> kickReasons = new ConcurrentHashMap<>();

    public StaffGui(ViceModuleContext ctx, StaffModule module, StaffPrompts prompts) {
        this.ctx = ctx;
        this.module = module;
        this.prompts = prompts;
    }

    // --- Main dashboard ---------------------------------------------------

    public void openMain(Player staff) {
        double tps = Bukkit.getTPS()[0];
        int online = Bukkit.getOnlinePlayers().size();
        var builder = ctx.gui().builder(GuiKit.title("Staff - Overview"), 6);
        GuiKit.frame(builder);
        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.SPYGLASS, "&6Staff oversight",
                "&7Inspect every module's raw data,",
                "&7economy totals and player details.",
                "&7Act-tier actions are logged."), GuiKit.NONE);
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.COMMAND_BLOCK)
                .name("&6Server overview")
                .lore("&7TPS: &f" + String.format("%.1f", tps),
                        "&7Players: &f" + online + "&7/" + Bukkit.getMaxPlayers(),
                        "&7Money supply: &f" + GuiKit.fmt(ctx.economy().totalSupply()),
                        "&7Transactions: &f" + ctx.economy().transactionCount(),
                        "&7Modules: &f" + modules().size())
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.GOLD_BLOCK, "&6Economy",
                "&7Money supply, top balances and",
                "&7full per-player transaction history."), (p, c) -> openEconomy(p, 1));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.CHEST, "&6Module data",
                "&7Raw stored data of every module:",
                "&7businesses, plots, gangs, licenses..."), (p, c) -> openModules(p, 1));
        builder.item(GuiKit.ACTION_3, GuiKit.playerHead(null, "&6Players",
                "&7Online players: health, food,",
                "&7location, balance, licenses."), (p, c) -> openPlayers(p, 1));
        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.MAP, "&6Regions",
                "&7All defined map regions and",
                "&7their tags."), (p, c) -> openRegions(p, 1));
        if (module.hasAct(staff)) {
            builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.REDSTONE, "&cPunishments",
                    "&7Active bans and mutes,",
                    "&7click to lift."), (p, c) -> openPunishments(p, 1));
        } else {
            builder.item(GuiKit.ACTION_5, actLocked("Punishments"), GuiKit.NONE);
        }
        builder.open(staff);
    }

    // --- Economy ----------------------------------------------------------

    public void openEconomy(Player staff, int page) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparingDouble(p -> -ctx.economy().balance(p.getUniqueId())));
        int pages = GuiKit.Pages.pages(online.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Staff - Economy"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the staff overview"), (p, c) -> openMain(p));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.GOLD_BLOCK)
                .name("&6Economy")
                .lore("&7Money supply: &f" + GuiKit.fmt(ctx.economy().totalSupply()),
                        "&7Transactions: &f" + ctx.economy().transactionCount(),
                        "&7Accounts: &f" + ctx.economy().allBalances().size(),
                        "&aClick a player for their history.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.EMERALD, "&6Top balances",
                "&7Richest accounts on the server."), (p, c) -> openTopBalances(p, 1));
        List<Player> slice = GuiKit.Pages.slice(online, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Player target = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, playerMoneyItem(target), (p, c) -> openPlayerEconomy(p, target, 1));
        }
        addPaging(builder, staff, safe, pages, (p, next) -> openEconomy(p, next));
        builder.open(staff);
    }

    public void openTopBalances(Player staff, int page) {
        Map<UUID, Double> balances = ctx.economy().allBalances();
        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(balances.entrySet());
        sorted.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
        int pages = GuiKit.Pages.pages(sorted.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Staff - Top balances"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the economy menu"), (p, c) -> openEconomy(p, 1));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.EMERALD, "&6Top balances",
                "&7Accounts: &f" + sorted.size()), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Map.Entry<UUID, Double>> slice = GuiKit.Pages.slice(sorted, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Map.Entry<UUID, Double> e = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.icon(Material.GOLD_INGOT,
                    "&e" + nameOf(e.getKey()),
                    "&7Balance: &f" + GuiKit.fmt(e.getValue())), GuiKit.NONE);
        }
        addPaging(builder, staff, safe, pages, (p, next) -> openTopBalances(p, next));
        builder.open(staff);
    }

    public void openPlayerEconomy(Player staff, Player target, int page) {
        List<Transaction> history = new ArrayList<>(ctx.economy().history(target.getUniqueId()));
        int pages = GuiKit.Pages.pages(history.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Staff - " + target.getName()), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the economy menu"), (p, c) -> openEconomy(p, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.GOLD_BLOCK)
                .name("&6" + target.getName())
                .lore("&7Balance: &f" + GuiKit.fmt(ctx.economy().balance(target.getUniqueId())),
                        "&7Transactions: &f" + history.size())
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Transaction> slice = GuiKit.Pages.slice(history, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            builder.item(GuiKit.GRID_FIRST + i, transactionItem(slice.get(i)), GuiKit.NONE);
        }
        if (module.hasSenior(staff)) {
            builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.EMERALD, "&aGive money",
                    "&7Deposit into their account.",
                    "&7Audited."), (p, c) -> openMoneyEditor(p, target, true));
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.RED_WOOL, "&cTake money",
                    "&7Withdraw from their account.",
                    "&7Audited."), (p, c) -> openMoneyEditor(p, target, false));
        }
        addPaging(builder, staff, safe, pages, (p, next) -> openPlayerEconomy(p, target, next));
        builder.open(staff);
    }

    private ItemStack transactionItem(Transaction tx) {
        boolean positive = tx.amount() >= 0;
        return ItemBuilder.of(positive ? Material.LIME_DYE : Material.GRAY_DYE)
                .name((positive ? "&a+" : "&c") + GuiKit.fmt(tx.amount()))
                .lore("&7" + tx.category(),
                        "&7Balance after: &f" + GuiKit.fmt(tx.balanceAfter()),
                        "&7" + STAMP.format(Instant.ofEpochMilli(tx.timestamp())))
                .build();
    }

    // --- Money actions ----------------------------------------------------

    public void openMoneyEditor(Player staff, Player target, boolean give) {
        MoneyDraft draft = moneyDrafts.computeIfAbsent(staff.getUniqueId(), k -> new MoneyDraft());
        if (draft.amount < 1000) {
            draft.amount = 5000;
        }
        var builder = ctx.gui().builder(GuiKit.title("Staff - " + (give ? "Give" : "Take") + " money"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("their account"), (p, c) -> openPlayerEconomy(p, target, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(give ? Material.EMERALD : Material.RED_WOOL)
                .name((give ? "&aGive" : "&cTake") + " &f" + GuiKit.fmt(draft.amount) + " &r" + (give ? "to" : "from") + " " + target.getName())
                .lore("&7Their balance: &f" + GuiKit.fmt(ctx.economy().balance(target.getUniqueId())),
                        "&7Adjust with the button below.",
                        "&7Confirmation is required.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, ItemBuilder.of(Material.GOLD_INGOT)
                .name("&6Amount: &f" + GuiKit.fmt(draft.amount))
                .lore("&7Left: &f+5,000", "&7Right: &f-5,000",
                        "&7Shift-left: &f+25,000", "&7Shift-right: &f-25,000")
                .build(), (p, c) -> {
            int step = c.isShiftClick() ? 25_000 : 5_000;
            int delta = (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) ? -step : step;
            draft.amount = Math.max(1000, Math.min(10_000_000, draft.amount + delta));
            openMoneyEditor(p, target, give);
        });
        builder.item(GuiKit.ACTION_4, GuiKit.cta(give ? Material.GREEN_WOOL : Material.RED_WOOL,
                "&aConfirm " + (give ? "give" : "take"),
                "&7This is logged in the audit log."), (p, c) -> confirmMoney(p, target, give));
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to their account."), (p, c) -> openPlayerEconomy(p, target, 1));
        builder.open(staff);
    }

    private void confirmMoney(Player staff, Player target, boolean give) {
        if (!module.hasSenior(staff)) {
            ctx.notifications().warn(staff, "You need " + StaffModule.PERM_SENIOR + " for this.");
            openPlayerEconomy(staff, target, 1);
            return;
        }
        if (target.getUniqueId().equals(staff.getUniqueId())) {
            ctx.notifications().warn(staff, "You cannot " + (give ? "give" : "take") + " money to/from yourself.");
            openPlayerEconomy(staff, target, 1);
            return;
        }
        if (module.isStaff(target.getUniqueId())) {
            ctx.notifications().warn(staff, "You cannot modify the money of another staff member.");
            openPlayerEconomy(staff, target, 1);
            return;
        }
        if (module.rateLimited(staff)) {
            ctx.notifications().warn(staff, "Too many actions this minute. Please wait.");
            openPlayerEconomy(staff, target, 1);
            return;
        }
        MoneyDraft draft = moneyDrafts.get(staff.getUniqueId());
        double amount = draft == null ? 0 : draft.amount;
        if (amount < 1000) {
            ctx.notifications().warn(staff, "Set a positive amount first.");
            openMoneyEditor(staff, target, give);
            return;
        }
        var result = give
                ? ctx.economy().deposit(target.getUniqueId(), amount, "staff grant")
                : ctx.economy().withdraw(target.getUniqueId(), amount, "staff take");
        if (!result.success()) {
            ctx.notifications().warn(staff, "Action failed: " + result.detail());
            return;
        }
        moneyDrafts.remove(staff.getUniqueId());
        module.log(staff, give ? "money-give" : "money-take", target.getName(), GuiKit.fmt(amount));
        ctx.notifications().msg(staff, "&a" + (give ? "Gave" : "Took") + " &f" + GuiKit.fmt(amount)
                + "&a " + (give ? "to" : "from") + " &f" + target.getName() + "&a.");
        openPlayerEconomy(staff, target, 1);
    }

    // --- Module raw data --------------------------------------------------

    public void openModules(Player staff, int page) {
        List<ViceModule> modules = modules();
        int pages = GuiKit.Pages.pages(modules.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Staff - Module data"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the staff overview"), (p, c) -> openMain(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.CHEST, "&6Module data",
                "&7Raw stored data of every module:",
                "&7businesses, plots, gangs, licenses..."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<ViceModule> slice = GuiKit.Pages.slice(modules, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            ViceModule mod = slice.get(i);
            int keys = ctx.storage().moduleDataAll(mod.id()).size();
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.icon(Material.CHEST,
                    "&e" + mod.displayName(),
                    "&7id: &f" + mod.id(),
                    "&7version: &f" + mod.version(),
                    "&7data keys: &f" + keys,
                    "&aClick to inspect."), (p, c) -> openModuleKeys(p, mod, 1));
        }
        addPaging(builder, staff, safe, pages, (p, next) -> openModules(p, next));
        builder.open(staff);
    }

    public void openModuleKeys(Player staff, ViceModule mod, int page) {
        List<String> keys = new ArrayList<>(ctx.storage().moduleDataAll(mod.id()).keySet());
        keys.sort(String::compareTo);
        int pages = GuiKit.Pages.pages(keys.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Data: " + mod.id()), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the module list"), (p, c) -> openModules(p, 1));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.CHEST,
                "&6" + mod.displayName(),
                "&7Keys: &f" + keys.size(),
                "&aClick a key to read its value."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<String> slice = GuiKit.Pages.slice(keys, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            String key = slice.get(i);
            String value = ctx.storage().moduleDataAll(mod.id()).get(key);
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.icon(Material.PAPER,
                    "&e" + key,
                    "&7" + compact(value)), (p, c) -> openModuleValue(p, mod, key, value, 1));
        }
        addPaging(builder, staff, safe, pages, (p, next) -> openModuleKeys(p, mod, next));
        builder.open(staff);
    }

    public void openModuleValue(Player staff, ViceModule mod, String key, String value, int page) {
        List<String> lines = wrap(value == null ? "null" : value, 140);
        int pages = GuiKit.Pages.pages(lines.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Value: " + key), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the module keys"), (p, c) -> openModuleKeys(p, mod, 1));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.PAPER,
                "&6" + mod.id() + " &7/ &f" + key,
                "&7Lines: &f" + lines.size()), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<String> slice = GuiKit.Pages.slice(lines, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.icon(Material.PAPER,
                    "&f" + slice.get(i)), GuiKit.NONE);
        }
        addPaging(builder, staff, safe, pages, (p, next) -> openModuleValue(p, mod, key, value, next));
        builder.open(staff);
    }

    // --- Players ----------------------------------------------------------

    public void openPlayers(Player staff, int page) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(Player::getName));
        int pages = GuiKit.Pages.pages(online.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Staff - Players"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the staff overview"), (p, c) -> openMain(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.PLAYER_HEAD,
                "&6Online players", "&7" + online.size() + " online."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Player> slice = GuiKit.Pages.slice(online, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Player target = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, playerMoneyItem(target), (p, c) -> openPlayerDetail(p, target));
        }
        addPaging(builder, staff, safe, pages, (p, next) -> openPlayers(p, next));
        builder.open(staff);
    }

    public void openPlayerDetail(Player staff, Player target) {
        List<String> licenses = module.businessModule() == null
                ? List.of()
                : new ArrayList<>(module.businessModule().manager().licenses(target.getUniqueId()));
        var builder = ctx.gui().builder(GuiKit.title("Staff - " + target.getName()), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the player list"), (p, c) -> openPlayers(p, 1));
        builder.item(GuiKit.STATUS, GuiKit.playerHead(target.getName(),
                "&6" + target.getName(),
                "&7Health: &f" + String.format("%.1f", target.getHealth()),
                "&7Food: &f" + target.getFoodLevel() + "&7/20",
                "&7Balance: &f" + GuiKit.fmt(ctx.economy().balance(target.getUniqueId())),
                "&7Location: &f" + target.getLocation().getWorld().getName()
                        + " " + target.getLocation().getBlockX()
                        + ", " + target.getLocation().getBlockY()
                        + ", " + target.getLocation().getBlockZ(),
                "&7Licenses: &f" + (licenses.isEmpty() ? "none" : String.join(", ", licenses))),
                GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.GOLD_BLOCK, "&6Account history",
                "&7Full transaction history of",
                "&7this player."), (p, c) -> openPlayerEconomy(p, target, 1));
        if (module.hasSenior(staff)) {
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.EMERALD, "&aGive money",
                    "&7Deposit into their account.",
                    "&7Audited."), (p, c) -> openMoneyEditor(p, target, true));
            builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.RED_WOOL, "&cTake money",
                    "&7Withdraw from their account.",
                    "&7Audited."), (p, c) -> openMoneyEditor(p, target, false));
            builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.BOOK, "&6License management",
                    "&7Issue or revoke business",
                    "&7licenses. Audited."), (p, c) -> openLicenseMenu(p, target));
        } else {
            builder.item(GuiKit.ACTION_2, seniorLocked("Give money"), GuiKit.NONE);
            builder.item(GuiKit.ACTION_3, seniorLocked("Take money"), GuiKit.NONE);
            builder.item(GuiKit.ACTION_4, seniorLocked("License management"), GuiKit.NONE);
        }
        if (module.hasAct(staff)) {
            builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.ENDER_EYE, "&dTeleport to",
                    "&7Teleport to their position.",
                    "&7Audited."), (p, c) -> confirmTeleport(p, target));
            builder.item(GuiKit.GRID_FIRST + 0, GuiKit.icon(Material.SHIELD, "&cModeration",
                    "&7Kick, ban, mute, freeze, spectate,",
                    "&7vanish and utility actions."), (p, c) -> openModeration(p, target));
            builder.item(GuiKit.GRID_FIRST + 1, GuiKit.icon(Material.ENDER_PEARL, "&dVanish",
                    "&7Toggle your own vanish."), (p, c) -> module.toggleVanish(p));
        } else {
            builder.item(GuiKit.ACTION_5, actLocked("Teleport to"), GuiKit.NONE);
            builder.item(GuiKit.GRID_FIRST + 0, actLocked("Moderation"), GuiKit.NONE);
        }
        builder.open(staff);
    }

    // --- Moderation -------------------------------------------------------

    public void openModeration(Player staff, Player target) {
        boolean frozen = module.isFrozen(target.getUniqueId());
        boolean muted = module.muteData(target.getUniqueId()).isPresent();
        boolean banned = module.activeBan(target.getUniqueId()).isPresent();
        var builder = ctx.gui().builder(GuiKit.title("Moderation - " + target.getName()), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the player detail"), (p, c) -> openPlayerDetail(p, target));
        builder.item(GuiKit.STATUS, GuiKit.playerHead(target.getName(),
                "&6" + target.getName(),
                "&7Balance: &f" + GuiKit.fmt(ctx.economy().balance(target.getUniqueId())),
                "&7Status: &f" + statusLine(frozen, muted, banned),
                "&7All actions are audited."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.IRON_BOOTS, "&cKick",
                "&7Disconnect them with a reason."), (p, c) -> openKickMenu(p, target));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.RED_WOOL, "&cBan",
                "&7Ban for a duration with",
                "&7a reason."), (p, c) -> openPunishMenu(p, target, "ban"));
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.BLUE_WOOL, "&9Mute",
                "&7Block their chat for a",
                "&7duration."), (p, c) -> openPunishMenu(p, target, "mute"));
        builder.item(GuiKit.ACTION_4, GuiKit.icon(frozen ? Material.LIME_WOOL : Material.GRAY_WOOL,
                frozen ? "&aUnfreeze" : "&cFreeze",
                frozen ? "&7Let them move again." : "&7Freeze them in place."), (p, c) -> toggleFreeze(p, target));
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.PURPLE_DYE, "&dSpectate",
                "&7Watch them in spectator mode.",
                "&7Click again to stop."), (p, c) -> toggleSpectate(p, target));
        builder.item(GuiKit.GRID_FIRST + 0, GuiKit.icon(Material.APPLE, "&aHeal",
                "&7Full health and food."), (p, c) -> healTarget(p, target));
        builder.item(GuiKit.GRID_FIRST + 1, GuiKit.icon(Material.HOPPER, "&eClear inventory",
                "&7Remove all items and armor."), (p, c) -> clearInv(p, target));
        builder.item(GuiKit.GRID_FIRST + 2, GuiKit.icon(Material.GRASS_BLOCK, "&2Gamemode",
                "&7Cycle survival / adventure /",
                "&7creative."), (p, c) -> cycleGamemode(p, target));
        builder.item(GuiKit.GRID_FIRST + 3, GuiKit.icon(Material.BOOK, "&6Punishments",
                "&7Ban and mute history of",
                "&7this player."), (p, c) -> openPlayerPunishments(p, target));
        builder.item(GuiKit.GRID_FIRST + 4, GuiKit.icon(Material.ENDER_PEARL, "&dVanish",
                "&7Toggle your own vanish."), (p, c) -> module.toggleVanish(p));
        int extra = 5;
        if (banned) {
            builder.item(GuiKit.GRID_FIRST + extra, GuiKit.cta(Material.GREEN_WOOL, "&aUnban",
                    "&7Remove the active ban."), (p, c) -> unbanTarget(p, target));
            extra++;
        }
        if (muted) {
            builder.item(GuiKit.GRID_FIRST + extra, GuiKit.cta(Material.GREEN_WOOL, "&aUnmute",
                    "&7Remove the active mute."), (p, c) -> unmuteTarget(p, target));
        }
        builder.open(staff);
    }

    private String statusLine(boolean frozen, boolean muted, boolean banned) {
        List<String> parts = new ArrayList<>();
        if (frozen) {
            parts.add("&cFrozen");
        }
        if (muted) {
            parts.add("&eMuted");
        }
        if (banned) {
            parts.add("&cBanned");
        }
        return parts.isEmpty() ? "&aNormal" : String.join("&7, ", parts);
    }

    public void openKickMenu(Player staff, Player target) {
        var builder = ctx.gui().builder(GuiKit.title("Kick - " + target.getName()), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("moderation"), (p, c) -> openModeration(p, target));
        builder.item(GuiKit.STATUS, GuiKit.playerHead(target.getName(),
                "&6Kick " + target.getName(),
                "&7A reason is required.",
                "&7Audited."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.WRITABLE_BOOK, "&eSet reason",
                "&7Type the reason in chat.",
                "&7Required before confirming."), (p, c) -> askKickReason(p, target));
        builder.item(GuiKit.ACTION_5, GuiKit.cta(Material.IRON_BOOTS, "&aConfirm kick",
                "&7This is logged in the audit log."), (p, c) -> confirmKick(p, target));
        builder.open(staff);
    }

    private void askKickReason(Player staff, Player target) {
        prompts.prompt(staff, "&eType the reason for kicking &f" + target.getName()
                + "&e in chat, or type &f'cancel'&e.", (p, reason) -> {
            if (reason.equalsIgnoreCase("cancel")) {
                ctx.notifications().msg(p, "&7Cancelled.");
                openKickMenu(p, target);
                return;
            }
            kickReasons.put(p.getUniqueId(), reason.trim());
            openKickMenu(p, target);
        });
    }

    private void confirmKick(Player staff, Player target) {
        String reason = kickReasons.getOrDefault(staff.getUniqueId(), "");
        if (reason.isEmpty()) {
            ctx.notifications().warn(staff, "Set a reason first (click 'Set reason').");
            openKickMenu(staff, target);
            return;
        }
        boolean ok = module.kickPlayer(staff, target, reason);
        kickReasons.remove(staff.getUniqueId());
        if (ok) {
            openPlayers(staff, 1);
        } else {
            openModeration(staff, target);
        }
    }

    public void openPunishMenu(Player staff, Player target, String type) {
        PunishDraft draft = punishDrafts.computeIfAbsent(staff.getUniqueId(), k -> new PunishDraft());
        boolean ban = "ban".equals(type);
        var builder = ctx.gui().builder(GuiKit.title((ban ? "Ban " : "Mute ") + target.getName()), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("moderation"), (p, c) -> openModeration(p, target));
        builder.item(GuiKit.STATUS, ItemBuilder.of(ban ? Material.RED_WOOL : Material.BLUE_WOOL)
                .name((ban ? "&cBan " : "&9Mute ") + "&f" + target.getName())
                .lore("&7Duration: &f" + StaffModule.durationLabel(draft.duration),
                        "&7Reason: &f" + (draft.reason.isEmpty() ? "&7not set" : draft.reason),
                        "&7Audited and rate limited.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.WRITABLE_BOOK, "&eSet reason",
                "&7Type the reason in chat.",
                "&7Required before confirming."), (p, c) -> askReason(p, target, type));
        long[] presets = {30 * MINUTE, HOUR, 6 * HOUR, DAY, 7 * DAY, 30 * DAY, 0};
        for (int i = 0; i < presets.length; i++) {
            long duration = presets[i];
            builder.item(GuiKit.GRID_FIRST + i, durationItem(duration, draft), (p, c) -> {
                draft.duration = duration;
                openPunishMenu(p, target, type);
            });
        }
        builder.item(GuiKit.ACTION_5, GuiKit.cta(ban ? Material.RED_WOOL : Material.BLUE_WOOL,
                "&aConfirm " + (ban ? "ban" : "mute"),
                "&7This is logged in the audit log."), (p, c) -> confirmPunish(p, target, type));
        builder.open(staff);
    }

    private ItemStack durationItem(long duration, PunishDraft draft) {
        boolean selected = draft.duration == duration;
        return ItemBuilder.of(selected ? Material.LIME_WOOL : Material.WHITE_WOOL)
                .name((selected ? "&a" : "&f") + StaffModule.durationLabel(duration)
                        + (selected ? " &7(selected)" : ""))
                .lore("&7Click to select this duration.")
                .build();
    }

    private void askReason(Player staff, Player target, String type) {
        prompts.prompt(staff, "&eType the reason for the " + type + " of &f" + target.getName()
                + "&e in chat, or type &f'cancel'&e.", (p, reason) -> {
            if (reason.equalsIgnoreCase("cancel")) {
                ctx.notifications().msg(p, "&7Cancelled.");
                openPunishMenu(p, target, type);
                return;
            }
            PunishDraft draft = punishDrafts.get(p.getUniqueId());
            if (draft != null) {
                draft.reason = reason.trim();
            }
            openPunishMenu(p, target, type);
        });
    }

    private void confirmPunish(Player staff, Player target, String type) {
        PunishDraft draft = punishDrafts.get(staff.getUniqueId());
        String reason = draft == null ? "" : draft.reason;
        long duration = draft == null ? 0 : draft.duration;
        if (reason.isEmpty()) {
            ctx.notifications().warn(staff, "Set a reason first (click 'Set reason').");
            openPunishMenu(staff, target, type);
            return;
        }
        if ("ban".equals(type)) {
            module.banPlayer(staff, target, reason, duration);
        } else {
            module.mutePlayer(staff, target, reason, duration);
        }
        punishDrafts.remove(staff.getUniqueId());
        openModeration(staff, target);
    }

    private void toggleFreeze(Player staff, Player target) {
        module.toggleFreeze(staff, target);
        openModeration(staff, target);
    }

    private void toggleSpectate(Player staff, Player target) {
        module.toggleSpectate(staff, target);
        openModeration(staff, target);
    }

    private void healTarget(Player staff, Player target) {
        module.healPlayer(staff, target);
        openModeration(staff, target);
    }

    private void clearInv(Player staff, Player target) {
        module.clearInventory(staff, target);
        openModeration(staff, target);
    }

    private void cycleGamemode(Player staff, Player target) {
        GameMode next = switch (target.getGameMode()) {
            case SURVIVAL -> GameMode.ADVENTURE;
            case ADVENTURE -> GameMode.CREATIVE;
            case CREATIVE -> GameMode.SPECTATOR;
            default -> GameMode.SURVIVAL;
        };
        module.setGameMode(staff, target, next);
        openModeration(staff, target);
    }

    private void unbanTarget(Player staff, Player target) {
        module.unban(staff, target.getUniqueId(), target.getName());
        openModeration(staff, target);
    }

    private void unmuteTarget(Player staff, Player target) {
        module.unmute(staff, target.getUniqueId(), target.getName());
        openModeration(staff, target);
    }

    public void openPunishments(Player staff, int page) {
        List<PunishmentRecord> entries = new ArrayList<>(module.activePunishments());
        int pages = GuiKit.Pages.pages(entries.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Staff - Punishments"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the staff overview"), (p, c) -> openMain(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.REDSTONE,
                "&6Active punishments", "&7" + entries.size() + " active.",
                "&aClick an entry to lift it."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<PunishmentRecord> slice = GuiKit.Pages.slice(entries, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            PunishmentRecord r = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, punishmentItem(r), (p, c) -> {
                liftPunishment(p, r);
                openPunishments(p, 1);
            });
        }
        addPaging(builder, staff, safe, pages, (p, next) -> openPunishments(p, next));
        builder.open(staff);
    }

    public void openPlayerPunishments(Player staff, Player target) {
        List<PunishmentRecord> records = new ArrayList<>(module.punishmentsFor(target.getUniqueId()));
        var builder = ctx.gui().builder(GuiKit.title("Punishments - " + target.getName()), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("moderation"), (p, c) -> openModeration(p, target));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.BOOK,
                "&6Punishments of " + target.getName(),
                "&7" + records.size() + " records."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<PunishmentRecord> slice = GuiKit.Pages.slice(records, 1, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            PunishmentRecord r = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, punishmentItem(r), (p, c) -> {
                if (!r.active(System.currentTimeMillis())) {
                    return;
                }
                liftPunishment(p, r);
                openPlayerPunishments(p, target);
            });
        }
        builder.open(staff);
    }

    private void liftPunishment(Player staff, PunishmentRecord r) {
        UUID uuid = UUID.fromString(r.uuid());
        if (r.isBan()) {
            module.unban(staff, uuid, r.name());
        } else {
            module.unmute(staff, uuid, r.name());
        }
    }

    private ItemStack punishmentItem(PunishmentRecord r) {
        return ItemBuilder.of(r.isBan() ? Material.RED_WOOL : Material.BLUE_WOOL)
                .name((r.isBan() ? "&c" : "&9") + (r.isBan() ? "Ban" : "Mute") + " &f" + r.name())
                .lore("&7Reason: &f" + r.reason(),
                        "&7Until: &f" + r.durationLabel(System.currentTimeMillis()),
                        "&7By: &f" + r.actor(),
                        "&7" + STAMP.format(Instant.ofEpochMilli(r.time())),
                        r.active(System.currentTimeMillis())
                                ? "&aClick to lift."
                                : "&7Expired.")
                .build();
    }

    // --- Licenses ---------------------------------------------------------

    public void openLicenseMenu(Player staff, Player target) {
        List<String> types = licenseTypes();
        var builder = ctx.gui().builder(GuiKit.title("Licenses: " + target.getName()), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the player detail"), (p, c) -> openPlayerDetail(p, target));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.NAME_TAG,
                "&6Licenses of " + target.getName(),
                "&7Green = licensed, gray = not.",
                "&7Click to toggle. Audited."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        for (int i = 0; i < types.size() && i < PER_PAGE; i++) {
            String type = types.get(i);
            boolean has = module.businessModule().manager().hasLicense(target.getUniqueId(), type);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(has ? Material.LIME_DYE : Material.GRAY_DYE)
                    .name((has ? "&a" : "&7") + type)
                    .lore(has ? "&7Licensed. Click to revoke."
                            : "&7Not licensed. Click to issue.")
                    .build(), (p, c) -> toggleLicense(p, target, type));
        }
        builder.open(staff);
    }

    private List<String> licenseTypes() {
        if (module.businessModule() == null) {
            return List.of();
        }
        List<String> types = module.businessModule().businessConfig().getStringList("license-required");
        if (types.isEmpty()) {
            return List.of("FARM", "SHOP", "MINE", "FOOD_COMPANY", "FACTORY",
                    "JEWELRY_STORE", "DEALERSHIP", "BANK");
        }
        return types;
    }

    private void toggleLicense(Player staff, Player target, String type) {
        if (!module.hasSenior(staff)) {
            ctx.notifications().warn(staff, "You need " + StaffModule.PERM_SENIOR + " for this.");
            openPlayerDetail(staff, target);
            return;
        }
        if (target.getUniqueId().equals(staff.getUniqueId()) || module.isStaff(target.getUniqueId())) {
            ctx.notifications().warn(staff, "You cannot change the license of a staff member.");
            openLicenseMenu(staff, target);
            return;
        }
        if (module.rateLimited(staff)) {
            ctx.notifications().warn(staff, "Too many actions this minute. Please wait.");
            return;
        }
        boolean has = module.businessModule().manager().hasLicense(target.getUniqueId(), type);
        if (has) {
            module.businessModule().manager().revokeLicense(target.getUniqueId(), type);
            module.log(staff, "license-revoke", target.getName(), type);
            ctx.notifications().msg(staff, "&cRevoked the &f" + type + "&c license from &f" + target.getName() + "&c.");
        } else {
            module.businessModule().manager().grantLicense(target.getUniqueId(), type);
            module.log(staff, "license-issue", target.getName(), type);
            ctx.notifications().msg(staff, "&aIssued the &f" + type + "&a license to &f" + target.getName() + "&a.");
        }
        openLicenseMenu(staff, target);
    }

    private void confirmTeleport(Player staff, Player target) {
        if (module.rateLimited(staff)) {
            ctx.notifications().warn(staff, "Too many actions this minute. Please wait.");
            return;
        }
        staff.teleport(target.getLocation());
        module.log(staff, "teleport", target.getName(), target.getLocation().getWorld().getName()
                + " " + target.getLocation().getBlockX() + "," + target.getLocation().getBlockY()
                + "," + target.getLocation().getBlockZ());
        ctx.notifications().msg(staff, "&dTeleported to &f" + target.getName() + "&d.");
    }

    // --- Regions ----------------------------------------------------------

    public void openRegions(Player staff, int page) {
        Collection<Region> regions = ctx.regions().all();
        List<Region> list = new ArrayList<>(regions);
        list.sort(Comparator.comparing(Region::id));
        int pages = GuiKit.Pages.pages(list.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Staff - Regions"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the staff overview"), (p, c) -> openMain(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.MAP,
                "&6Regions", "&7" + list.size() + " defined."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Region> slice = GuiKit.Pages.slice(list, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Region r = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.icon(Material.MAP,
                    "&e" + r.id(),
                    "&7" + r.serialized(),
                    "&7Tags: &f" + (r.tags().isEmpty() ? "-" : String.join(", ", r.tags()))), GuiKit.NONE);
        }
        addPaging(builder, staff, safe, pages, (p, next) -> openRegions(p, next));
        builder.open(staff);
    }

    // --- Audit log --------------------------------------------------------

    public void openAudit(Player staff, int page) {
        List<AuditEntry> entries = new ArrayList<>(module.auditEntries());
        Collections.reverse(entries);
        int pages = GuiKit.Pages.pages(entries.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Staff - Audit log"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the staff overview"), (p, c) -> openMain(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.PAPER,
                "&6Audit log", "&7Entries: &f" + entries.size()), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<AuditEntry> slice = GuiKit.Pages.slice(entries, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            AuditEntry e = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.PAPER)
                    .name("&f" + STAMP.format(Instant.ofEpochMilli(e.time())) + " &7" + e.actor())
                    .lore("&7Action: &f" + e.action(),
                            "&7Target: &f" + (e.target().isEmpty() ? "-" : e.target()),
                            "&7Detail: &f" + (e.detail().isEmpty() ? "-" : e.detail()))
                    .build(), GuiKit.NONE);
        }
        addPaging(builder, staff, safe, pages, (p, next) -> openAudit(p, next));
        builder.open(staff);
    }

    // --- Helpers ----------------------------------------------------------

    private List<ViceModule> modules() {
        return new ArrayList<>(ViceCore.get().getModuleRegistry().modules());
    }

    private ItemStack actLocked(String label) {
        return ItemBuilder.of(Material.GRAY_DYE)
                .name("&7" + label)
                .lore("&7Requires " + StaffModule.PERM_ACT + ".")
                .build();
    }

    private ItemStack seniorLocked(String label) {
        return ItemBuilder.of(Material.GRAY_DYE)
                .name("&7" + label)
                .lore("&7Requires " + StaffModule.PERM_SENIOR + ".")
                .build();
    }

    private ItemStack playerMoneyItem(Player target) {
        return GuiKit.playerHead(target.getName(),
                "&e" + target.getName(),
                "&7Balance: &f" + GuiKit.fmt(ctx.economy().balance(target.getUniqueId())),
                "&7Health: &f" + String.format("%.1f", target.getHealth()),
                "&aClick to inspect.");
    }

    private String compact(String value) {
        if (value == null) {
            return "&7null";
        }
        String v = value.replace("\n", " ");
        return v.length() > 90 ? v.substring(0, 90) + "..." : v;
    }

    private List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            lines.add("null");
            return lines;
        }
        String clean = text.replace("\r", "");
        for (String part : clean.split("\n", -1)) {
            while (part.length() > width) {
                lines.add(part.substring(0, width));
                part = part.substring(width);
            }
            lines.add(part);
        }
        return lines;
    }

    private String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    private void addPaging(GUIService.GuiBuilder builder, Player player, int page, int pages, PagingAction action) {
        if (pages <= 1) {
            return;
        }
        builder.item(GuiKit.PAGE_PREV, page > 1 ? GuiKit.prevPage(page) : GuiKit.pageGap(), (p, c) -> {
            if (page > 1) {
                action.accept(p, page - 1);
            }
        });
        builder.item(GuiKit.PAGE_INDICATOR, GuiKit.pageIndicator(page, pages), GuiKit.NONE);
        builder.item(GuiKit.PAGE_NEXT, page < pages ? GuiKit.nextPage(page) : GuiKit.pageGap(), (p, c) -> {
            if (page < pages) {
                action.accept(p, page + 1);
            }
        });
    }

    @FunctionalInterface
    private interface PagingAction {
        void accept(Player player, int page);
    }

    private static final class MoneyDraft {
        int amount = 5000;
    }

    private static final class PunishDraft {
        long duration = DAY;
        String reason = "";
    }
}
