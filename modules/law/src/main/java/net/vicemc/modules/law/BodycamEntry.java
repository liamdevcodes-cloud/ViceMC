package net.vicemc.modules.law;

import java.util.UUID;

/**
 * A single bodycam event recorded during a police interaction.
 */
public final class BodycamEntry {

    public long timestamp;
    public UUID officer;
    public UUID target;
    public String action;
    public String charge;
}
