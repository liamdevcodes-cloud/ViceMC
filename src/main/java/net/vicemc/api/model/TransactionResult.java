package net.vicemc.api.model;

/**
 * Outcome of an economy operation. {@code credited} may differ from
 * {@code amount} when deposit handlers (e.g. garnishment) apply.
 */
public record TransactionResult(boolean success, double amount, double credited, String category, String detail) {

    public static TransactionResult ok(double amount, String category) {
        return new TransactionResult(true, amount, amount, category, "");
    }

    public static TransactionResult fail(double amount, String category, String detail) {
        return new TransactionResult(false, amount, 0, category, detail);
    }
}
