package net.vicemc.modules.gangs;

import com.google.gson.reflect.TypeToken;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.Json;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The two fixed gangs (North Side / South Side), memberships, elected leaders,
 * persisted votes, weekly drug claims and contested territories.
 */
public final class GangManager {

    /** The only gangs that exist on the server; config entries outside this set are ignored. */
    public static final Set<String> CANONICAL_IDS = Set.of("north", "south");

    private static final TypeToken<Map<String, String>> STRING_MAP = new TypeToken<Map<String, String>>() {
    };
    private static final TypeToken<Map<String, Map<String, String>>> VOTE_MAP = new TypeToken<Map<String, Map<String, String>>>() {
    };

    private final ViceModuleContext ctx;
    private final Map<String, Gang> gangs = new LinkedHashMap<>();
    private final Map<UUID, String> memberships = new ConcurrentHashMap<>();
    private final Map<String, UUID> leaders = new ConcurrentHashMap<>();
    private final Map<String, Map<UUID, UUID>> votes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> drugClaims = new ConcurrentHashMap<>();
    private final Map<String, Territory> territories = new LinkedHashMap<>();

    public GangManager(ViceModuleContext ctx) {
        this.ctx = ctx;
        loadConfig();
        loadMemberships();
        loadVotes();
        loadTerritories();
    }

    private void loadConfig() {
        ConfigurationSection section = ctx.yaml("gangs.yml").getSection("gangs");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            if (!CANONICAL_IDS.contains(id)) {
                continue;
            }
            Gang gang = new Gang();
            gang.id = id;
            gang.name = section.getString(id + ".name", id);
            gang.regionTag = section.getString(id + ".region-tag", "gang:" + id);
            gang.drugMaterial = section.getString(id + ".drug-material", "SUGAR");
            gang.drugName = section.getString(id + ".drug-name", "Drugs");
            gang.drugEffect = section.getString(id + ".drug-effect", "HASTE");
            gang.drugAmplifier = section.getInt(id + ".drug-amplifier", 1);
            gang.drugDurationSeconds = section.getInt(id + ".drug-duration-seconds", 60);
            gangs.put(id, gang);
        }
    }

    private void loadMemberships() {
        ctx.storage().getModuleData("gangs", "members").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, STRING_MAP.getType());
            if (map != null) {
                map.forEach((k, v) -> {
                    if (CANONICAL_IDS.contains(v)) {
                        memberships.put(UUID.fromString(k), v);
                    }
                });
            }
        });
        ctx.storage().getModuleData("gangs", "leaders").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, STRING_MAP.getType());
            if (map != null) {
                map.forEach((k, v) -> {
                    if (CANONICAL_IDS.contains(k)) {
                        leaders.put(k, UUID.fromString(v));
                    }
                });
            }
        });
        ctx.storage().getModuleData("gangs", "drugs").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, STRING_MAP.getType());
            if (map != null) {
                map.forEach((k, v) -> drugClaims.put(UUID.fromString(k), Long.parseLong(v)));
            }
        });
    }

    private void loadVotes() {
        ctx.storage().getModuleData("gangs", "votes").ifPresent(json -> {
            Map<String, Map<String, String>> map = Json.fromJson(json, VOTE_MAP.getType());
            if (map == null) {
                return;
            }
            map.forEach((gang, votesByVoter) -> {
                Map<UUID, UUID> inner = new ConcurrentHashMap<>();
                votesByVoter.forEach((v, c) -> inner.put(UUID.fromString(v), UUID.fromString(c)));
                votes.put(gang, inner);
            });
        });
    }

    private void loadTerritories() {
        ConfigurationSection section = ctx.yaml("gangs.yml").getSection("territories");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            Territory t = new Territory();
            t.id = id;
            t.name = section.getString(id + ".name", id);
            t.regionTag = section.getString(id + ".region-tag", "gangzone:" + id);
            t.captureSeconds = Math.max(10, section.getInt(id + ".capture-seconds", 60));
            territories.put(id, t);
        }
        ctx.storage().getModuleData("gangs", "territories").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, STRING_MAP.getType());
            if (map != null) {
                map.forEach((id, owner) -> {
                    Territory t = territories.get(id);
                    if (t != null && gangs.containsKey(owner)) {
                        t.owner = owner;
                    }
                });
            }
        });
        ctx.storage().getModuleData("gangs", "territoryProgress").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, STRING_MAP.getType());
            if (map != null) {
                map.forEach((id, val) -> {
                    Territory t = territories.get(id);
                    if (t == null) {
                        return;
                    }
                    try {
                        String[] parts = val.split(";");
                        t.progress = Integer.parseInt(parts[0]);
                        if (parts.length > 1 && gangs.containsKey(parts[1])) {
                            t.progressOwner = parts[1];
                        }
                    } catch (NumberFormatException ignored) {
                    }
                });
            }
        });
    }

    // --- gangs ------------------------------------------------------------

    public Gang gang(String id) {
        return gangs.get(id);
    }

    public List<Gang> all() {
        return new ArrayList<>(gangs.values());
    }

    // --- memberships & leaders --------------------------------------------

    public String gangOf(UUID uuid) {
        return memberships.get(uuid);
    }

    public boolean isMember(UUID uuid, String gangId) {
        return gangId.equals(memberships.get(uuid));
    }

    public void join(UUID uuid, String gangId) {
        memberships.put(uuid, gangId);
        save();
    }

    public void leave(UUID uuid) {
        String gang = memberships.remove(uuid);
        if (gang != null && gang.equals(leaders.get(gang))) {
            leaders.remove(gang);
        }
        save();
    }

    public UUID leaderOf(String gangId) {
        return leaders.get(gangId);
    }

    public boolean hasLeader(String gangId) {
        return leaders.containsKey(gangId);
    }

    public void setLeader(String gangId, UUID uuid) {
        leaders.put(gangId, uuid);
        save();
    }

    public List<UUID> membersOf(String gangId) {
        return memberships.entrySet().stream()
                .filter(e -> e.getValue().equals(gangId))
                .map(Map.Entry::getKey)
                .toList();
    }

    // --- elections (votes persist across restarts) ------------------------

    public void vote(String gangId, UUID voter, UUID candidate) {
        votes.computeIfAbsent(gangId, k -> new ConcurrentHashMap<>()).put(voter, candidate);
        saveVotes();
    }

    public Map<UUID, UUID> votesFor(String gangId) {
        return votes.getOrDefault(gangId, Map.of());
    }

    public void clearVotes() {
        votes.clear();
        saveVotes();
    }

    /** Highest vote count; on a tie the incumbent leader wins, otherwise the first candidate. */
    public UUID countWinner(String gangId) {
        Map<UUID, Integer> tally = new HashMap<>();
        votes.getOrDefault(gangId, Map.of()).values().forEach(c -> tally.merge(c, 1, Integer::sum));
        int best = tally.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (best == 0) {
            return null;
        }
        List<UUID> top = tally.entrySet().stream()
                .filter(e -> e.getValue() == best)
                .map(Map.Entry::getKey)
                .toList();
        if (top.size() == 1) {
            return top.get(0);
        }
        UUID incumbent = leaders.get(gangId);
        if (incumbent != null && top.contains(incumbent)) {
            return incumbent;
        }
        return top.get(0);
    }

    // --- drugs ------------------------------------------------------------

    public int claimDrug(UUID uuid, int limit) {
        long week = java.time.LocalDate.now().toEpochDay() / 7;
        String key = "drugs:" + uuid;
        int used = ctx.storage().getModuleData("gangs", key)
                .filter(v -> v.startsWith(week + ":"))
                .map(v -> Integer.parseInt(v.split(":")[1]))
                .orElse(0);
        if (used >= limit) {
            return -1;
        }
        ctx.storage().setModuleData("gangs", key, week + ":" + (used + 1));
        return used + 1;
    }

    // --- territories ------------------------------------------------------

    public List<Territory> territories() {
        return new ArrayList<>(territories.values());
    }

    public Territory territory(String id) {
        return territories.get(id);
    }

    public int territoriesControlled(String gangId) {
        return (int) territories.values().stream().filter(t -> gangId.equals(t.owner)).count();
    }

    public boolean controlsAny(String gangId) {
        return territoriesControlled(gangId) > 0;
    }

    public void captureTerritory(String id, String gangId) {
        Territory t = territories.get(id);
        if (t == null) {
            return;
        }
        t.owner = gangId;
        t.progress = 0;
        t.progressOwner = "";
        saveTerritories();
    }

    public void updateProgress(String id, int progress, String progressOwner) {
        Territory t = territories.get(id);
        if (t == null) {
            return;
        }
        int p = Math.max(0, Math.min(100, progress));
        String po = progressOwner == null ? "" : progressOwner;
        if (t.progress == p && t.progressOwner.equals(po)) {
            return;
        }
        t.progress = p;
        t.progressOwner = po;
        saveTerritories();
    }

    // --- persistence ------------------------------------------------------

    private void save() {
        Map<String, String> memberMap = new HashMap<>();
        memberships.forEach((k, v) -> memberMap.put(k.toString(), v));
        ctx.storage().setModuleData("gangs", "members", Json.toJson(memberMap));
        Map<String, String> leaderMap = new HashMap<>();
        leaders.forEach((k, v) -> leaderMap.put(k, v.toString()));
        ctx.storage().setModuleData("gangs", "leaders", Json.toJson(leaderMap));
    }

    private void saveVotes() {
        Map<String, Map<String, String>> out = new HashMap<>();
        votes.forEach((gang, inner) -> {
            Map<String, String> m = new HashMap<>();
            inner.forEach((v, c) -> m.put(v.toString(), c.toString()));
            out.put(gang, m);
        });
        ctx.storage().setModuleData("gangs", "votes", Json.toJson(out));
    }

    private void saveTerritories() {
        Map<String, String> owners = new HashMap<>();
        Map<String, String> progress = new HashMap<>();
        for (Territory t : territories.values()) {
            if (!t.owner.isEmpty()) {
                owners.put(t.id, t.owner);
            }
            progress.put(t.id, t.progress + (t.progressOwner.isEmpty() ? "" : ";" + t.progressOwner));
        }
        ctx.storage().setModuleData("gangs", "territories", Json.toJson(owners));
        ctx.storage().setModuleData("gangs", "territoryProgress", Json.toJson(progress));
    }
}
