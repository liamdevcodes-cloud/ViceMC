package net.vicemc.api.model;

import java.util.UUID;

/**
 * A single economy ledger entry. Amount is negative for withdrawals.
 */
public record Transaction(long id, UUID player, double amount, String category, long timestamp, double balanceAfter) {
}
