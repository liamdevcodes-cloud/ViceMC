package net.vicemc.modules.banking;

import net.vicemc.api.model.DepositHandler;

import java.util.UUID;

/**
 * Garnishment handler: while a borrower has defaulted loans, 10-20% of every
 * future deposit is diverted to repay the debt. Registered into the core
 * economy service by the banking module.
 */
public final class GarnishmentHandler implements DepositHandler {

    private final LoanManager loans;
    private final double rate;

    public GarnishmentHandler(LoanManager loans, double rate) {
        this.loans = loans;
        this.rate = rate;
    }

    @Override
    public double apply(UUID player, double amount, String category) {
        if (category.startsWith("loan repayment")) {
            return amount;
        }
        double maxGarnish = amount * rate;
        double remainingDebt = loans.defaultedFor(player).stream().mapToDouble(l -> l.remaining).sum();
        if (remainingDebt <= 0.01) {
            return amount;
        }
        double garnish = Math.min(maxGarnish, remainingDebt);
        for (Loan loan : loans.defaultedFor(player)) {
            if (garnish <= 0.01) {
                break;
            }
            double pay = Math.min(garnish, loan.remaining);
            loan.remaining -= pay;
            if (loan.remaining <= 0.001) {
                loan.status = "REPAID";
            }
            garnish -= pay;
        }
        loans.save();
        return Math.max(0, amount - Math.min(maxGarnish, remainingDebt));
    }

    @Override
    public String description() {
        return "garnishment";
    }
}
