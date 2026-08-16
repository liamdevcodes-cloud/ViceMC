package net.vicemc.api.service;

import net.vicemc.api.model.DepositHandler;
import net.vicemc.api.model.Transaction;
import net.vicemc.api.model.TransactionResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Central dollar ledger. All money flows go through this service so that every
 * transaction is recorded and deposit-side deductions (garnishment, fees)
 * always apply.
 */
public interface EconomyService {

    double balance(UUID player);

    TransactionResult deposit(UUID player, double amount, String category);

    TransactionResult withdraw(UUID player, double amount, String category);

    TransactionResult transfer(UUID from, UUID to, double amount, String category);

    List<Transaction> history(UUID player);

    /** The last {@code limit} transactions of a player, newest first. */
    List<Transaction> history(UUID player, int limit);

    /** Every account and its current balance. */
    Map<UUID, Double> allBalances();

    /** Sum of all account balances - the total money in circulation. */
    double totalSupply();

    /** The most recent transactions across all players, newest first. */
    List<Transaction> recentTransactions(int limit);

    /** Income/expense totals by category across all players. */
    Map<String, Double> categoryTotals();

    /** Income/expense totals by category for one player. */
    Map<String, Double> categoryTotals(UUID player);

    long transactionCount();

    void addDepositHandler(DepositHandler handler);

    void removeDepositHandler(DepositHandler handler);
}
