package net.vicemc.modules.gangs;

import java.util.Set;
import java.util.UUID;

/**
 * A faction (North Side / South Side) with elected leader, lieutenants,
 * members, kill stats per election term, and a shared bank.
 */
public final class Gang {

    public String id;
    public String name;
    public String regionTag;
    public String drugMaterial;
    public String drugName;
    public String drugEffect;
    public int drugAmplifier;
    public int drugDurationSeconds;

    /** UUID of the currently elected leader (null if no leader). */
    public UUID leaderUUID;

    /** Lieutenants appointed by the leader. */
    public final Set<UUID> lieutenantUUIDs = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<UUID, Boolean>());

    /** All member UUIDs (including leader and lieutenants). */
    public final Set<UUID> memberUUIDs = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<UUID, Boolean>());

    /** Kills this election term: player UUID -> kill count. */
    public final java.util.Map<UUID, Integer> killsThisTerm = new java.util.concurrent.ConcurrentHashMap<>();

    /** Total kills all-time (persists across terms). */
    public final java.util.Map<UUID, Integer> killsAllTime = new java.util.concurrent.ConcurrentHashMap<>();

    /** Gang bank balance. */
    public double bankBalance;

    /** Whether a territory war event is currently active for this gang. */
    public boolean warActive;
}
