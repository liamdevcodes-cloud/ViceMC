package net.vicemc.modules.economy;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.model.Transaction;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Balance, pay and admin economy commands on top of the core ledger.
 */
public final class EconomyModule implements ViceModule {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private ViceModuleContext ctx;
    private WalletGui gui;

    @Override
    public String id() {
        return "economy";
    }

    @Override
    public String displayName() {
        return "Vice Economy";
    }

    @Override
    public String version() {
        return "1.1.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.gui = new WalletGui(ctx);

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("wallet")
                .aliases("money", "bal")
                .permission("vicemc.economy.balance")
                .description("Open your wallet")
                .executes(this::wallet)
                .tabulates((c, a) -> a.size() <= 1
                        ? List.of("history", "pay")
                        : playerNames())
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("balance")
                .permission("vicemc.economy.balance")
                .description("Show your balance")
                .executes(this::balance)
                .tabulates((c, a) -> playerNames())
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("pay")
                .permission("vicemc.economy.pay")
                .description("Send money to another player")
                .executes(this::pay)
                .tabulates((c, a) -> playerNames())
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("economy")
                .aliases("econ")
                .permission("vicemc.economy.admin")
                .description("Admin economy overview and manipulation")
                .executes(this::admin)
                .tabulates((c, a) -> a.size() <= 1
                        ? List.of("give", "take", "set", "supply", "top", "history", "categories")
                        : playerNames())
                .build());

        ctx.logger().info("Economy commands ready.");
    }

    private void wallet(CommandContext c) {
        if (!c.isPlayer()) {
            c.error("Only players can open their wallet.");
            return;
        }
        gui.openDashboard(c.player());
    }

    private void balance(CommandContext c) {
        UUID target;
        if (c.size() > 0) {
            target = c.uuidArg(0);
            if (target == null) {
                c.error("Player not found.");
                return;
            }
        } else if (c.isPlayer()) {
            target = c.player().getUniqueId();
        } else {
            c.error("Console must specify a player.");
            return;
        }

        double bal = ctx.economy().balance(target);
        c.msg("&7Balance of &f" + c.arg(0, "you") + "&7: &a" + Text.moneyPlain(bal));

        if (c.isPlayer() && (target.equals(c.player().getUniqueId())
                || c.player().hasPermission("vicemc.economy.admin"))) {
            c.msg("&7Recent transactions:");
            ctx.economy().history(target).stream().limit(5).forEach(t ->
                    c.msg("  &8" + (t.amount() >= 0 ? "&a+" : "&c") + Text.moneyPlain(t.amount())
                            + " &7" + t.category()));
        }
    }

    private void pay(CommandContext c) {
        if (!c.isPlayer()) {
            c.error("Only players can pay.");
            return;
        }
        if (c.size() < 2) {
            c.usage("/pay <player> <amount>");
            return;
        }
        Player target = c.playerArg(0);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        double amount = c.argDouble(1, -1);
        if (amount <= 0) {
            c.error("Invalid amount.");
            return;
        }
        var result = ctx.economy().transfer(c.player().getUniqueId(), target.getUniqueId(), amount, "pay");
        if (!result.success()) {
            c.error("Payment failed: " + result.detail());
            return;
        }
        c.msg("&aPaid &f" + Text.moneyPlain(amount) + "&a to &f" + target.getName());
        ctx.notifications().msg(target, "&a" + c.player().getName() + " paid you &f" + Text.moneyPlain(amount));
    }

    private void admin(CommandContext c) {
        if (c.size() < 1) {
            c.usage("/economy <give|take|set|supply|top|history|categories> ...");
            return;
        }
        switch (c.arg(0)) {
            case "give", "take", "set" -> manipulate(c);
            case "supply" -> supply(c);
            case "top" -> top(c);
            case "history" -> history(c);
            case "categories" -> categories(c);
            default -> c.usage("/economy <give|take|set|supply|top|history|categories> ...");
        }
    }

    private void manipulate(CommandContext c) {
        if (c.size() < 3) {
            c.usage("/economy <give|take|set> <player> <amount>");
            return;
        }
        UUID target = c.uuidArg(1);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        double amount = c.argDouble(2, -1);
        if (amount < 0) {
            c.error("Invalid amount.");
            return;
        }
        switch (c.arg(0)) {
            case "give" -> {
                ctx.economy().deposit(target, amount, "admin give");
                c.msg("&aGave &f" + Text.moneyPlain(amount));
            }
            case "take" -> {
                var r = ctx.economy().withdraw(target, amount, "admin take");
                c.msg(r.success() ? "&aTook &f" + Text.moneyPlain(amount) : "&cFailed: " + r.detail());
            }
            case "set" -> {
                double current = ctx.economy().balance(target);
                double delta = amount - current;
                if (delta >= 0) {
                    ctx.economy().deposit(target, delta, "admin set");
                } else {
                    ctx.economy().withdraw(target, -delta, "admin set");
                }
                c.msg("&aSet balance to &f" + Text.moneyPlain(amount));
            }
            default -> c.usage("/economy <give|take|set> <player> <amount>");
        }
    }

    private void supply(CommandContext c) {
        double total = ctx.economy().totalSupply();
        int accounts = ctx.economy().allBalances().size();
        long tx = ctx.economy().transactionCount();
        c.msg("&6Total money supply: &f" + Text.moneyPlain(total));
        c.msg("&7Accounts: &f" + accounts + " &7| &7Transactions: &f" + tx);
        c.msg("&7Average balance: &f" + Text.moneyPlain(accounts == 0 ? 0 : total / accounts));
    }

    private void top(CommandContext c) {
        int n = c.argInt(1, 10);
        n = Math.max(1, Math.min(30, n));
        List<Map.Entry<UUID, Double>> sorted = ctx.economy().allBalances().entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Comparator.comparingDouble((Map.Entry<UUID, Double> e) -> e.getValue()).reversed())
                .limit(n)
                .toList();
        c.msg("&6Richest balances (top " + sorted.size() + "):");
        int rank = 1;
        for (Map.Entry<UUID, Double> e : sorted) {
            c.msg("  &8" + rank++ + ". &f" + nameOf(e.getKey()) + " &7- &a" + Text.moneyPlain(e.getValue()));
        }
    }

    private void history(CommandContext c) {
        if (c.size() < 2) {
            c.usage("/economy history <player> [count]");
            return;
        }
        UUID target = c.uuidArg(1);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        int limit = c.argInt(2, 20);
        limit = Math.max(1, Math.min(200, limit));
        List<Transaction> history = ctx.economy().history(target, limit);
        c.msg("&6Transactions of &f" + c.arg(1) + "&6 (" + history.size() + "):");
        for (Transaction t : history) {
            c.msg("  &8" + TIME.format(Instant.ofEpochMilli(t.timestamp()))
                    + " &7" + (t.amount() >= 0 ? "&a+" : "&c") + Text.moneyPlain(t.amount())
                    + " &7" + t.category() + " &8(bal " + Text.moneyPlain(t.balanceAfter()) + ")");
        }
    }

    private void categories(CommandContext c) {
        Map<String, Double> totals;
        String who;
        if (c.size() > 1) {
            UUID target = c.uuidArg(1);
            if (target == null) {
                c.error("Player not found.");
                return;
            }
            totals = ctx.economy().categoryTotals(target);
            who = c.arg(1);
        } else {
            totals = ctx.economy().categoryTotals();
            who = "server-wide";
        }
        c.msg("&6Income/expense by category &7(" + who + "):");
        totals.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, Double> e) -> e.getValue()).reversed())
                .forEach(e -> c.msg("  &f" + (e.getKey() == null || e.getKey().isEmpty() ? "(none)" : e.getKey())
                        + " &7- " + (e.getValue() >= 0 ? "&a" : "&c") + Text.moneyPlain(e.getValue())));
    }

    private String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    private List<String> playerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }
}
