package net.vicemc.modules.serial;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * A limited run of serialized items. The template item is stored base64-encoded
 * and every copy of the run is tracked in {@link #copies}. A run can be voided
 * as a whole, which voids every copy in it.
 */
public final class SerialRun {

    public String serial;
    public String template;
    public int count;
    public long createdAt;
    public boolean voided;
    public Map<String, SerialCopy> copies = new ConcurrentHashMap<>();

    public SerialRun() {
    }

    public int remaining() {
        int remaining = 0;
        for (SerialCopy copy : copies.values()) {
            if (!copy.claimed && !copy.voided) {
                remaining++;
            }
        }
        return remaining;
    }

    public SerialCopy copyById(String id) {
        return copies.get(id);
    }

    public SerialCopy copyByIndex(int index) {
        for (SerialCopy copy : copies.values()) {
            if (copy.index == index) {
                return copy;
            }
        }
        return null;
    }
}
