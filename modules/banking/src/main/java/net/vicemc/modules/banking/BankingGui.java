package net.vicemc.modules.banking;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.model.Transaction;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player-facing banking menu. Shows balances, borrowed and lent loans,
 * transaction history and a guided loan-creation wizard - all in the dark
 * modern menu style instead of chat commands.
 */
public final class BankingGui {

    private static final int PER_PAGE = 27;
    private static final double[] AMOUNT_STEPS = {1_000, 5_000, 10_000, 50_000};
    private static final int[] TERMS = {3, 7, 14, 21, 30};

    private final ViceModuleContext ctx;
    private final LoanManager loans;
    private final BankingModule module;
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> listPage = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> historyPage = new ConcurrentHashMap<>();

    public BankingGui(ViceModuleContext ctx, LoanManager loans, BankingModule module) {
        this.ctx = ctx;
        this.loans = loans;
        this.module = module;
    }

    public void openDashboard(Player player) {
        UUID uuid = player.getUniqueId();
        double borrowed = 0;
        for (Loan loan : loans.forBorrower(uuid)) {
            if ("ACTIVE".equals(loan.status)) {
                borrowed += loan.remaining;
            }
        }
        double lent = 0;
        for (Loan loan : loans.forLender(uuid)) {
            if ("ACTIVE".equals(loan.status)) {
                lent += loan.remaining;
            }
        }
        boolean defaulted = loans.hasDefaulted(uuid);

        var builder = ctx.gui().builder(GuiKit.title("Bank"), 6);
        GuiKit.frame(builder);
        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&6How it works",
                "&7Borrow loans from other players or",
                "&7lend your own money. Repayment is",
                "&7automatic every day. Defaults are",
                "&7referred to the courts and garnish."), GuiKit.NONE);

        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.GOLD_INGOT)
                .name("&6Your finances")
                .lore("&7Balance: &a" + GuiKit.fmt(ctx.economy().balance(uuid)),
                        "&7Borrowed: &c" + GuiKit.fmt(borrowed),
                        "&7Lent out: &a" + GuiKit.fmt(lent),
                        defaulted ? "&4You have defaulted loans under garnishment."
                                : "&7No active default cases.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.WRITABLE_BOOK, "&cLoans I borrowed",
                "&7Review and repay the loans",
                "&7you owe."), (p, c) -> openBorrowed(p, 1));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.EMERALD, "&aLoans I lent",
                "&7Track money you have lent out."), (p, c) -> openLent(p, 1));
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.ANVIL, "&6New loan",
                "&7Issue a loan to another player",
                "&7with interest and a term."), (p, c) -> {
            if (!p.hasPermission("vicemc.bank.loan")) {
                ctx.notifications().warn(p, "You are not licensed to lend money.");
                return;
            }
            openNewLoan(p);
        });
        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.CLOCK, "&eTransaction history",
                "&7Browse your recent payments."), (p, c) -> openHistory(p, 1));
        builder.item(GuiKit.ACTION_5, GuiKit.icon(defaulted ? Material.REDSTONE : Material.LIME_DYE,
                defaulted ? "&4Garnishment active" : "&aNo garnishment",
                defaulted ? "&7A share of deposits is being taken"
                        + "&7to settle your defaulted loans."
                        : "&7You have no defaulted loans."), GuiKit.NONE);

        List<Transaction> history = ctx.economy().history(uuid);
        int shown = Math.min(history.size(), 27);
        for (int i = 0; i < shown; i++) {
            Transaction t = history.get(i);
            builder.item(GuiKit.GRID_FIRST + i, transactionItem(t, i), GuiKit.NONE);
        }

        builder.open(player);
    }

    private void openBorrowed(Player player, int page) {
        UUID uuid = player.getUniqueId();
        List<Loan> list = new ArrayList<>(loans.forBorrower(uuid));
        list.sort((a, b) -> Boolean.compare("ACTIVE".equals(a.status), "ACTIVE".equals(b.status)));
        openLoanList(player, "Loans I borrowed", list, page, true, () -> openDashboard(player));
    }

    private void openLent(Player player, int page) {
        UUID uuid = player.getUniqueId();
        List<Loan> list = new ArrayList<>(loans.forLender(uuid));
        list.sort((a, b) -> Boolean.compare("ACTIVE".equals(a.status), "ACTIVE".equals(b.status)));
        openLoanList(player, "Loans I lent", list, page, false, () -> openDashboard(player));
    }

    private void openLoanList(Player player, String title, List<Loan> list, int page,
                              boolean canPay, Runnable back) {
        int pages = GuiKit.Pages.pages(list.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title(title), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the bank menu"), (p, c) -> back.run());
        builder.item(GuiKit.STATUS, GuiKit.icon(canPay ? Material.WRITABLE_BOOK : Material.EMERALD,
                "&6" + title,
                "&7" + list.size() + " loan(s).",
                canPay ? "&7Click a loan to repay it." : "&7Lenders can review, not repay."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        List<Loan> slice = GuiKit.Pages.slice(list, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Loan loan = slice.get(i);
            int slot = GuiKit.GRID_FIRST + i;
            boolean active = "ACTIVE".equals(loan.status);
            Material mat = active ? (canPay ? Material.REDSTONE_TORCH : Material.GREEN_DYE)
                    : ("REPAID".equals(loan.status) ? Material.LIME_DYE : Material.GRAY_DYE);
            String statusColor = active ? "&6ACTIVE" : "&8" + loan.status;
            ItemStack item = ItemBuilder.of(mat)
                    .name("&fLoan #" + loan.id)
                    .lore("&7" + (canPay ? "Lender: &f" + nameOf(loan.lender)
                            : "Borrower: &f" + nameOf(loan.borrower)),
                            "&7Remaining: &c" + GuiKit.fmt(loan.remaining) + "&7/&f" + GuiKit.fmt(loan.totalOwed()),
                            "&7Daily payment: &f" + GuiKit.fmt(loan.dailyPayment()),
                            "&7Status: " + statusColor,
                            canPay && active ? "&aClick to repay" : "")
                    .build();
            if (canPay && active) {
                int id = loan.id;
                builder.item(slot, item, (p, c) -> openPay(p, id));
            } else {
                builder.item(slot, item, GuiKit.NONE);
            }
        }

        addPaging(builder, player, safe, pages, (p, next) -> {
            if (canPay) {
                openBorrowed(p, next);
            } else {
                openLent(p, next);
            }
        });
        builder.open(player);
    }

    private void openPay(Player player, int loanId) {
        Loan loan = loans.byId(loanId).orElse(null);
        if (loan == null || !"ACTIVE".equals(loan.status)) {
            ctx.notifications().warn(player, "That loan is no longer active.");
            openBorrowed(player, 1);
            return;
        }
        int stepIndex = 0;
        var builder = ctx.gui().builder(GuiKit.title("Repay loan #" + loanId), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("your loans"), (p, c) -> openBorrowed(p, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.WRITABLE_BOOK)
                .name("&6Loan #" + loan.id)
                .lore("&7Lender: &f" + nameOf(loan.lender),
                        "&7Remaining: &c" + GuiKit.fmt(loan.remaining),
                        "&7Daily payment: &f" + GuiKit.fmt(loan.dailyPayment()))
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.RED_WOOL, "&cPay " + GuiKit.fmt(AMOUNT_STEPS[stepIndex]),
                "&7Left click: &f" + GuiKit.fmt(AMOUNT_STEPS[stepIndex]),
                "&7Shift-left: &f" + GuiKit.fmt(AMOUNT_STEPS[stepIndex] * 10),
                "&aClick to pay this amount."), (p, c) -> {
            double amount = switch (c) {
                case SHIFT_LEFT -> AMOUNT_STEPS[stepIndex] * 10;
                default -> AMOUNT_STEPS[stepIndex];
            };
            module.repay(p, loanId, amount);
            if (loans.byId(loanId).map(l -> "ACTIVE".equals(l.status)).orElse(false)) {
                openPay(p, loanId);
            } else {
                openBorrowed(p, 1);
            }
        });
        builder.item(GuiKit.ACTION_3, GuiKit.cta(Material.GOLD_INGOT, "&aPay the full remaining balance",
                "&7" + GuiKit.fmt(loan.remaining)), (p, c) -> {
            module.repay(p, loanId, loan.remaining);
            openBorrowed(p, 1);
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.LIME_DYE, "&aDaily payment",
                "&7Auto-paid each day: &f" + GuiKit.fmt(loan.dailyPayment())), GuiKit.NONE);
        builder.open(player);
    }

    private void openHistory(Player player, int page) {
        UUID uuid = player.getUniqueId();
        List<Transaction> history = new ArrayList<>(ctx.economy().history(uuid));
        int pages = GuiKit.Pages.pages(history.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Transaction history"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the bank menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.CLOCK, "&eTransaction history",
                "&7" + history.size() + " recorded transaction(s)."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Transaction> slice = GuiKit.Pages.slice(history, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            builder.item(GuiKit.GRID_FIRST + i, transactionItem(slice.get(i), i), GuiKit.NONE);
        }
        addPaging(builder, player, safe, pages, (p, next) -> openHistory(p, next));
        builder.open(player);
    }

    private ItemStack transactionItem(Transaction t, int offset) {
        boolean positive = t.amount() >= 0;
        return ItemBuilder.of(positive ? Material.LIME_DYE : Material.RED_DYE)
                .name((positive ? "&a+" : "&c") + GuiKit.fmt(t.amount()))
                .lore("&7" + t.category(),
                        "&8" + timeAgo(t.timestamp()),
                        "&7Balance after: &f" + GuiKit.fmt(t.balanceAfter()))
                .build();
    }

    private void openNewLoan(Player player) {
        Draft draft = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        if (draft.borrower == null) {
            List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            online.removeIf(p -> p.getUniqueId().equals(player.getUniqueId()));
            if (!online.isEmpty()) {
                draft.borrower = online.get(0).getUniqueId();
            }
        }
        renderWizard(player);
    }

    private void renderWizard(Player player) {
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) {
            return;
        }
        var builder = ctx.gui().builder(GuiKit.title("New loan"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the bank menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.ANVIL)
                .name("&6Loan summary")
                .lore("&7Borrower: &f" + (draft.borrower == null ? "&7none" : nameOf(draft.borrower)),
                        "&7Amount: &a" + GuiKit.fmt(draft.amount),
                        "&7Interest: &e" + (int) (draft.rate * 100) + "%",
                        "&7Term: &f" + draft.days + " days",
                        "&8Total due: &f" + GuiKit.fmt(draft.amount * (1 + draft.rate)))
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.PLAYER_HEAD, "&eBorrower",
                "&7" + (draft.borrower == null ? "&7none" : "&f" + nameOf(draft.borrower)),
                "&7Click to pick an online player."), (p, c) -> openBorrowerPicker(p, 1));

        double amount = draft.amount;
        builder.item(GuiKit.ACTION_2, ItemBuilder.of(Material.GOLD_INGOT)
                .name("&aAmount: &f" + GuiKit.fmt(amount))
                .lore("&7Left: &f+1,000", "&7Right: &f-1,000",
                        "&7Shift-left: &f+10,000", "&7Shift-right: &f-10,000")
                .build(), (p, c) -> {
            long step = c.isShiftClick() ? 10_000 : 1_000;
            long delta = (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) ? -step : step;
            draft.amount = Math.max(1_000, Math.min(5_000_000, draft.amount + delta));
            renderWizard(p);
        });

        int ratePct = (int) (draft.rate * 100);
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.EXPERIENCE_BOTTLE, "&eInterest: &f" + ratePct + "%",
                "&7Click to cycle: 0, 5, 10, 15, 20, 25%"), (p, c) -> {
            draft.rate = ((ratePct + 5) % 30) / 100.0;
            renderWizard(p);
        });

        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.CLOCK, "&eTerm: &f" + draft.days + " days",
                "&7Click to cycle the term length."), (p, c) -> {
            int next = (draft.termIndex + 1) % TERMS.length;
            draft.termIndex = next;
            draft.days = TERMS[next];
            renderWizard(p);
        });

        builder.item(GuiKit.ACTION_5, GuiKit.cta(Material.GREEN_WOOL, "&aConfirm loan",
                "&7Issue this loan to " + (draft.borrower == null ? "nobody" : nameOf(draft.borrower)),
                "&7Your money is transferred immediately."), (p, c) -> {
            if (draft.borrower == null) {
                ctx.notifications().warn(p, "Pick a borrower first.");
                return;
            }
            boolean ok = module.lend(p, draft.borrower, draft.amount, draft.rate, draft.days);
            drafts.remove(p.getUniqueId());
            if (ok) {
                openDashboard(p);
            } else {
                renderWizard(p);
            }
        });

        builder.open(player);
    }

    private void openBorrowerPicker(Player player, int page) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.removeIf(p -> p.getUniqueId().equals(player.getUniqueId()));
        int pages = GuiKit.Pages.pages(online.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Pick a borrower"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the new loan menu"), (p, c) -> renderWizard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.PLAYER_HEAD, "&ePick a borrower",
                "&7" + online.size() + " player(s) online."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Player> slice = GuiKit.Pages.slice(online, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Player target = slice.get(i);
            int slot = GuiKit.GRID_FIRST + i;
            builder.item(slot, GuiKit.playerHead(target.getName(),
                    "&e" + target.getName(),
                    "&7Balance: &f" + GuiKit.fmt(ctx.economy().balance(target.getUniqueId())),
                    "&aClick to select."), (p, c) -> {
                drafts.get(p.getUniqueId()).borrower = target.getUniqueId();
                renderWizard(p);
            });
        }
        addPaging(builder, player, safe, pages, (p, next) -> openBorrowerPicker(p, next));
        builder.open(player);
    }

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

    private String nameOf(UUID uuid) {
        if (uuid == null) {
            return "unknown";
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    private String timeAgo(long millis) {
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

    private static final class Draft {
        UUID borrower;
        double amount = 10_000;
        double rate = 0.10;
        int termIndex = 1;
        int days = TERMS[1];
    }
}
