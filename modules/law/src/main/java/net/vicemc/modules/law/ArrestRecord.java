package net.vicemc.modules.law;

import java.util.UUID;

/**
 * A current arrest record. If releaseAt is set, the suspect is released
 * automatically when that time passes.
 */
public final class ArrestRecord {

    public UUID player;
    public UUID officer;
    public String charge;
    public long jailedAt;
    public long releaseAt;
}
