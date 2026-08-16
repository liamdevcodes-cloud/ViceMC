package net.vicemc.modules.heists;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Live state of one running heist.
 */
public final class Heist {

    public int id;
    public String siteId;
    public UUID leader;
    public List<UUID> robbers = new ArrayList<>();
    public long startedAt;
    public long endsAt;
    public String phase = "RUNNING";
    public UUID hostageId;
    public UUID boatId;
    public volatile boolean allMasked;
    public ScheduledTask monitorTask;
}
