package net.vicemc.modules.banking;

import java.util.UUID;

/**
 * A loan issued by one player to another. The lender's own capital is at
 * risk, which encourages real risk assessment.
 */
public final class Loan {

    public int id;
    public UUID lender;
    public UUID borrower;
    public double principal;
    public double interestRate;
    public int termDays;
    public long createdAt;
    public double remaining;
    public int missedPayments;
    public String status = "ACTIVE"; // ACTIVE, REPAID, DEFAULTED

    public double totalOwed() {
        return principal * (1 + interestRate);
    }

    public double dailyPayment() {
        return totalOwed() / termDays;
    }
}
