package net.vicemc.modules.banking;

import com.google.gson.reflect.TypeToken;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.Json;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class LoanManager {

    private static final TypeToken<List<Loan>> LOANS_TYPE = new TypeToken<List<Loan>>() {
    };

    private final ViceModuleContext ctx;
    private final List<Loan> loans = new CopyOnWriteArrayList<>();

    public LoanManager(ViceModuleContext ctx) {
        this.ctx = ctx;
        ctx.storage().getModuleData("banking", "loans").ifPresent(json -> {
            List<Loan> loaded = Json.fromJson(json, LOANS_TYPE.getType());
            if (loaded != null) {
                loans.addAll(loaded);
            }
        });
    }

    public Loan create(UUID lender, UUID borrower, double amount, double rate, int days) {
        Loan loan = new Loan();
        loan.id = nextId();
        loan.lender = lender;
        loan.borrower = borrower;
        loan.principal = amount;
        loan.interestRate = rate;
        loan.termDays = days;
        loan.createdAt = System.currentTimeMillis();
        loan.remaining = loan.totalOwed();
        loans.add(loan);
        save();
        return loan;
    }

    public void save() {
        ctx.storage().setModuleData("banking", "loans", Json.toJson(loans));
    }

    private int nextId() {
        return loans.stream().mapToInt(l -> l.id).max().orElse(0) + 1;
    }

    public Optional<Loan> byId(int id) {
        return loans.stream().filter(l -> l.id == id).findFirst();
    }

    public List<Loan> all() {
        return loans;
    }

    public List<Loan> active() {
        return loans.stream().filter(l -> "ACTIVE".equals(l.status)).toList();
    }

    public List<Loan> forBorrower(UUID uuid) {
        return loans.stream().filter(l -> l.borrower.equals(uuid)).toList();
    }

    public List<Loan> forLender(UUID uuid) {
        return loans.stream().filter(l -> l.lender.equals(uuid)).toList();
    }

    public boolean hasDefaulted(UUID uuid) {
        return loans.stream().anyMatch(l -> l.borrower.equals(uuid)
                && "DEFAULTED".equals(l.status) && l.remaining > 0.001);
    }

    public List<Loan> defaultedFor(UUID uuid) {
        return loans.stream().filter(l -> l.borrower.equals(uuid)
                && "DEFAULTED".equals(l.status) && l.remaining > 0.001).toList();
    }

    /**
     * Runs the daily repayment cycle. Defaults escalate through the
     * government/court layer before garnishment kicks in.
     */
    public void processDaily(int missedBeforeDefault) {
        for (Loan loan : active()) {
            var result = ctx.economy().withdraw(loan.borrower, loan.dailyPayment(), "loan repayment #" + loan.id);
            if (result.success()) {
                ctx.economy().deposit(loan.lender, result.amount(), "loan repayment #" + loan.id);
                loan.remaining = Math.max(0, loan.remaining - result.amount());
                if (loan.remaining <= 0.001) {
                    loan.status = "REPAID";
                    ctx.logger().info("Loan #" + loan.id + " repaid.");
                }
            } else {
                loan.missedPayments++;
                if (loan.missedPayments >= missedBeforeDefault) {
                    loan.status = "DEFAULTED";
                    ctx.logger().warning("Loan #" + loan.id + " defaulted by borrower " + loan.borrower);
                    ctx.events().publish("finance", java.util.Map.of(
                            "type", "loan-default",
                            "loanId", loan.id,
                            "borrower", loan.borrower.toString(),
                            "amount", loan.remaining));
                }
            }
        }
        save();
    }
}
