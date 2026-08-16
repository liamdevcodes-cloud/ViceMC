package net.vicemc.api.service;

import org.bukkit.block.Block;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Map-wide build protection. By default the map cannot be built on or broken;
 * players with builder mode on (or an admin bypass) may build. Modules claim
 * the blocks they manage (plot interiors, farm crops) so their own listeners
 * keep deciding what happens there.
 */
public interface BuildProtectionService {

    boolean builderMode(UUID uuid);

    void setBuilderMode(UUID uuid, boolean on);

    /** Registers a claim so blocks it matches are left to the claiming module. */
    void claim(Predicate<Block> claim);

    /** True if any module claims this block. */
    boolean isManaged(Block block);
}
