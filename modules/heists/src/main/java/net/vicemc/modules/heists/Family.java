package net.vicemc.modules.heists;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A mafia-style family. Registered families can recruit members and organize
 * heists.
 */
public final class Family {

    public int id;
    public String name;
    public UUID owner;
    public List<UUID> members = new ArrayList<>();
    public long createdAt;
}
