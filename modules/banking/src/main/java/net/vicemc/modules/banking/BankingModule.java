package net.vicemc.modules.banking;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.model.DepositHandler;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Player-driven banking: lenders risk their own money, interest is their
 * choice, repayment is automatic, and defaults escalate to garnishment.
 */
public final class BankingModule implements ViceModule {

    private ViceModuleContext ctx;
    private LoanManager loans;
    private YamlConfig config;
    private BankingGui gui;
    private DepositHandler garnishment;
    private ScheduledTask dailyTask;
    private long lastDay = -1;

    @Override
    public String id() {
        return "banking";
    }

    @Override
    public String displayName() {
        return "Vice Banking";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("banking.yml");
        this.loans = new LoanManager(ctx);
        this.gui = new BankingGui(ctx, loans, this);

        double rate = clamp(config.getDouble("garnishment-rate", 0.15), 0.10, 0.20);
        this.garnishment = new GarnishmentHandler(loans, rate);
        ctx.economy().addDepositHandler(garnishment);

        registerCommands();

        dailyTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(),
                task -> tickDaily(), 20L, 1200L);

        ctx.logger().info("Banking module ready.");
    }

    private void tickDaily() {
        long day = LocalDate.now().toEpochDay();
        if (lastDay == -1) {
            lastDay = day;
            return;
        }
        if (day != lastDay) {
            lastDay = day;
            int missed = config.getInt("missed-payments-before-default", 2);
            loans.processDaily(missed);
        }
    }

    @Override
    public void onDisable() {
        if (dailyTask != null) {
            cancelQuietly(dailyTask);
            dailyTask = null;
        }
        if (garnishment != null) {
            ctx.economy().removeDepositHandler(garnishment);
        }
        if (loans != null) {
            loans.save();
        }
    }

    private void registerCommands() {
        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("bank")
                .aliases("banking", "loan")
                .description("Player-driven banking and loans")
                .executes(this::bank)
                .tabulates((c, a) -> {
                    if (a.size() <= 1) {
                        return List.of("create", "history", "list", "pay");
                    }
                    if ("create".equals(a.get(0)) || "pay".equals(a.get(0))) {
                        return playerNames();
                    }
                    return List.of();
                })
                .build());
    }

    private void bank(CommandContext c) {
        if (!c.isPlayer()) {
            c.error("This is a player command - run it in game to open the bank GUI.");
            return;
        }
        switch (c.arg(0)) {
            case "create" -> create(c);
            case "history" -> history(c);
            case "list" -> list(c);
            case "pay" -> pay(c);
            default -> gui.openDashboard(c.player());
        }
    }

    private void create(CommandContext c) {
        if (!c.isPlayer()) {
            c.error("Only players can lend.");
            return;
        }
        if (!c.player().hasPermission("vicemc.bank.loan")) {
            c.error("You are not licensed to lend money.");
            return;
        }
        if (c.size() < 4) {
            c.usage("/loan create <borrower> <amount> <interest%> <days>");
            return;
        }
        UUID borrower = c.uuidArg(1);
        if (borrower == null) {
            c.error("Borrower not found.");
            return;
        }
        lend(c.player(), borrower, c.argDouble(2, -1), c.argDouble(3, -1) / 100.0, c.argInt(4, -1));
    }

    public boolean lend(Player lender, UUID borrower, double amount, double rate, int days) {
        if (borrower == null || amount <= 0 || rate < 0) {
            lender.sendMessage(Text.color("&cInvalid loan details."));
            return false;
        }
        int minDays = config.getInt("min-term-days", 3);
        int maxDays = config.getInt("max-term-days", 30);
        if (days < minDays || days > maxDays) {
            lender.sendMessage(Text.color("&cTerm must be between " + minDays + " and " + maxDays + " days."));
            return false;
        }

        Loan loan = loans.create(lender.getUniqueId(), borrower, amount, rate, days);
        var transfer = ctx.economy().transfer(lender.getUniqueId(), borrower, amount, "loan principal #" + loan.id);
        if (!transfer.success()) {
            loans.all().remove(loan);
            loans.save();
            lender.sendMessage(Text.color("&cYou cannot afford that loan: " + transfer.detail()));
            return false;
        }
        lender.sendMessage(Text.color("&aLoan #" + loan.id + " issued: &f" + Text.moneyPlain(amount)
                + "&a at " + (rate * 100) + "% over " + days + " days."));
        Player borrowerPlayer = Bukkit.getPlayer(borrower);
        if (borrowerPlayer != null) {
            ctx.notifications().msg(borrowerPlayer, "&a" + lender.getName() + " issued you a loan of &f"
                    + Text.moneyPlain(amount) + "&a. Repay over " + days + " days or face garnishment.");
        }
        return true;
    }

    private void history(CommandContext c) {
        if (!c.isPlayer()) {
            c.error("Only players can inspect history.");
            return;
        }
        if (!c.player().hasPermission("vicemc.bank.loan")) {
            c.error("Not licensed.");
            return;
        }
        if (c.size() < 2) {
            c.usage("/loan history <player>");
            return;
        }
        UUID target = c.uuidArg(1);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        c.msg("&6Transaction history of &f" + c.arg(1) + "&6:");
        ctx.economy().history(target).stream().limit(20).forEach(t ->
                c.msg("  &8" + (t.amount() >= 0 ? "&a+" : "&c") + Text.moneyPlain(t.amount())
                        + " &7" + t.category()));
        List<Loan> list = loans.forBorrower(target);
        if (!list.isEmpty()) {
            c.msg("&6Loans:");
            list.forEach(l -> c.msg("  &8#" + l.id + " &f" + Text.moneyPlain(l.remaining)
                    + "&7 remaining [" + l.status + "]"));
        }
    }

    private void list(CommandContext c) {
        UUID uuid = c.isPlayer() ? c.player().getUniqueId() : null;
        if (c.size() > 1 && c.player() != null && c.player().hasPermission("vicemc.bank.loan")) {
            uuid = c.uuidArg(1);
        }
        if (uuid == null) {
            c.error("Specify a player.");
            return;
        }
        List<Loan> list = new ArrayList<>(loans.forBorrower(uuid));
        list.addAll(loans.forLender(uuid));
        if (list.isEmpty()) {
            c.msg("&7No loans found.");
            return;
        }
        c.msg("&6Loans:");
        list.forEach(l -> c.msg("  &8#" + l.id + " &f" + Text.moneyPlain(l.remaining) + "&7/" + Text.moneyPlain(l.totalOwed())
                + " [" + l.status + "]"));
    }

    private void pay(CommandContext c) {
        if (!c.isPlayer()) {
            c.error("Only players can repay.");
            return;
        }
        if (c.size() < 3) {
            c.usage("/loan pay <id> <amount>");
            return;
        }
        int id = c.argInt(1, -1);
        double amount = c.argDouble(2, -1);
        repay(c.player(), id, amount);
    }

    public boolean repay(Player borrower, int id, double amount) {
        Loan loan = loans.byId(id).orElse(null);
        if (loan == null || !loan.borrower.equals(borrower.getUniqueId())) {
            borrower.sendMessage(Text.color("&cLoan not found."));
            return false;
        }
        if (amount <= 0) {
            borrower.sendMessage(Text.color("&cInvalid amount."));
            return false;
        }
        var result = ctx.economy().transfer(loan.borrower, loan.lender, amount, "loan repayment #" + id);
        if (!result.success()) {
            borrower.sendMessage(Text.color("&cCannot pay: " + result.detail()));
            return false;
        }
        loan.remaining = Math.max(0, loan.remaining - result.amount());
        if (loan.remaining <= 0.001) {
            loan.status = "REPAID";
        }
        loans.save();
        borrower.sendMessage(Text.color("&aPaid &f" + Text.moneyPlain(amount) + "&a toward loan #" + id + "."));
        return true;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private List<String> playerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private static void cancelQuietly(ScheduledTask task) {
        try {
            task.cancel();
        } catch (IllegalStateException ignored) {
        }
    }
}
