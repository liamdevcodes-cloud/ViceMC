package net.vicemc.core.service.impl;

import net.vicemc.api.service.BuildProtectionService;
import net.vicemc.api.service.StorageService;
import org.bukkit.block.Block;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * SQLite-backed builder-mode state plus the registry of blocks modules claim
 * as their own (so core leaves them to the module's listener).
 */
public final class BuildProtectionServiceImpl implements BuildProtectionService {

    private static final String PREFIX = "buildermode:";

    private final StorageService storage;
    private final Set<Predicate<Block>> claims = ConcurrentHashMap.newKeySet();

    public BuildProtectionServiceImpl(StorageService storage) {
        this.storage = storage;
    }

    @Override
    public boolean builderMode(UUID uuid) {
        return "true".equalsIgnoreCase(storage.get(PREFIX + uuid).orElse("false"));
    }

    @Override
    public void setBuilderMode(UUID uuid, boolean on) {
        storage.put(PREFIX + uuid, String.valueOf(on));
    }

    @Override
    public void claim(Predicate<Block> claim) {
        if (claim != null) {
            claims.add(claim);
        }
    }

    @Override
    public boolean isManaged(Block block) {
        for (Predicate<Block> claim : claims) {
            if (claim.test(block)) {
                return true;
            }
        }
        return false;
    }
}
