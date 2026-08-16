package net.vicemc.modules.serial;

/**
 * A single tracked copy inside a {@link SerialRun}. Each copy gets a unique id
 * so it can be tracked and voided individually, even if the run is not.
 */
public final class SerialCopy {

    public String id;
    public int index;
    public boolean claimed;
    public long claimedAt;
    public String claimedBy;
    public boolean voided;

    public SerialCopy() {
    }

    public SerialCopy(String id, int index) {
        this.id = id;
        this.index = index;
    }
}
