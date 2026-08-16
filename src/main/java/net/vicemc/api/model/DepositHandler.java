package net.vicemc.api.model;

import java.util.UUID;

/**
 * Hook that can reduce a deposit before it is credited. Used by the banking
 * module for garnishment of defaulted loans.
 */
public interface DepositHandler {

    /**
     * @return the amount that should actually be credited to the player
     */
    double apply(UUID player, double amount, String category);

    String description();
}
