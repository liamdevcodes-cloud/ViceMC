package net.vicemc.modules.law;

import com.google.gson.reflect.TypeToken;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.Json;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Arrests: jail placement, automatic release after served time, and the
 * waiting room used while a lawyer reviews bodycam footage.
 */
public final class ArrestManager {

    private static final TypeToken<List<ArrestRecord>> ARRESTS_TYPE = new TypeToken<List<ArrestRecord>>() {
    };

    private final ViceModuleContext ctx;
    private final YamlConfig config;
    private final List<ArrestRecord> arrests = new CopyOnWriteArrayList<>();

    public ArrestManager(ViceModuleContext ctx, YamlConfig config) {
        this.ctx = ctx;
        this.config = config;
        ctx.storage().getModuleData("law", "arrests").ifPresent(json -> {
            List<ArrestRecord> loaded = Json.fromJson(json, ARRESTS_TYPE.getType());
            if (loaded != null) {
                arrests.addAll(loaded);
            }
        });
    }

    public void arrest(UUID player, UUID officer, String charge, int jailDays) {
        ArrestRecord record = new ArrestRecord();
        record.player = player;
        record.officer = officer;
        record.charge = charge;
        record.jailedAt = System.currentTimeMillis();
        record.releaseAt = jailDays > 0 ? System.currentTimeMillis() + jailDays * 86400_000L : 0;
        arrests.add(record);
        save();
        Player target = Bukkit.getPlayer(player);
        if (target != null) {
            Location jail = Locations.parse(config.getString("locations.jail", "world,0,64,0"));
            if (jail != null && jail.getWorld() != null) {
                target.teleport(jail);
            }
        }
    }

    public boolean isArrested(UUID player) {
        return arrests.stream().anyMatch(a -> a.player.equals(player));
    }

    public Optional<ArrestRecord> recordOf(UUID player) {
        return arrests.stream().filter(a -> a.player.equals(player)).findFirst();
    }

    public List<ArrestRecord> all() {
        return arrests;
    }

    public void release(UUID player) {
        arrests.removeIf(a -> a.player.equals(player));
        save();
        Player target = Bukkit.getPlayer(player);
        if (target != null) {
            Location loc = Locations.parse(config.getString("locations.release", "world,0,64,0"));
            if (loc != null && loc.getWorld() != null) {
                target.teleport(loc);
            }
            ctx.notifications().msg(target, "&aYou have been released.");
        }
    }

    public void toWaitingRoom(UUID player) {
        Player target = Bukkit.getPlayer(player);
        if (target != null) {
            Location loc = Locations.parse(config.getString("locations.waiting-room", "world,0,64,0"));
            if (loc != null && loc.getWorld() != null) {
                target.teleport(loc);
            }
        }
    }

    /**
     * Releases any arrest whose sentence has been served. Teleports are
     * dispatched onto each released player's own thread.
     */
    public void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (ArrestRecord record : arrests) {
            if (record.releaseAt > 0 && now >= record.releaseAt) {
                arrests.remove(record);
                changed = true;
                Player player = Bukkit.getPlayer(record.player);
                if (player != null) {
                    Location loc = Locations.parse(config.getString("locations.release", "world,0,64,0"));
                    player.getScheduler().run(ctx.plugin(), task -> {
                        if (player.isOnline()) {
                            if (loc != null && loc.getWorld() != null) {
                                player.teleport(loc);
                            }
                            ctx.notifications().msg(player, "&aYour sentence has been served - you are released.");
                        }
                    }, null);
                }
            }
        }
        if (changed) {
            save();
        }
    }

    public void save() {
        ctx.storage().setModuleData("law", "arrests", Json.toJson(arrests));
    }
}
