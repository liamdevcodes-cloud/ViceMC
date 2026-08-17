package net.vicemc.modules.gangs;

import com.google.gson.reflect.TypeToken;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.Json;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The two fixed gangs, memberships, elected leaders, lieutenants, kill stats,
 * votes, weekly drug claims, gang banks, and contested territories.
 */
public final class GangManager {

    public static final Set<String> CANONICAL_IDS = Set.of("north", "south");
    private static final int MAX_MEMBER_DIFF = 4;

    private static final TypeToken<Map<String, String>> STRING_MAP = new TypeToken<>() {};
    private static final TypeToken<Map<String, Map<String, String>>> VOTE_MAP = new TypeToken<>() {};
    private static final TypeToken<Map<String, Integer>> INT_MAP = new TypeToken<>() {};

    private final ViceModuleContext ctx;
    private final Map<String, Gang> gangs = new LinkedHashMap<>();
    private final Map<String, UUID> leaders = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> lieutenants = new ConcurrentHashMap<>();
    private final Map<String, Map<UUID, UUID>> votes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> drugClaims = new ConcurrentHashMap<>();
    private final Map<String, Territory> territories = new LinkedHashMap<>();

    public GangManager(ViceModuleContext ctx) {
        this.ctx = ctx;
        loadConfig();
        loadGangData();
        loadTerritories();
    }

    // ========================= CONFIG LOAD =========================

    private void loadConfig() {
        ConfigurationSection section = ctx.yaml("gangs.yml").getSection("gangs");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            if (!CANONICAL_IDS.contains(id)) continue;
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

    // ========================= PERSISTENCE LOAD =========================

    private void loadGangData() {
        // Memberships
        ctx.storage().getModuleData("gangs", "members").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, STRING_MAP.getType());
            if (map != null) {
                map.forEach((k, v) -> {
                    UUID uuid = UUID.fromString(k);
                    String gangId = v;
                    if (CANONICAL_IDS.contains(gangId)) {
                        Gang gang = gangs.get(gangId);
                        if (gang != null) {
                            gang.memberUUIDs.add(uuid);
                        }
                    }
                });
            }
        });

        // Leaders
        ctx.storage().getModuleData("gangs", "leaders").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, STRING_MAP.getType());
            if (map != null) {
                map.forEach((gangId, uuidStr) -> {
                    if (CANONICAL_IDS.contains(gangId)) {
                        UUID uuid = UUID.fromString(uuidStr);
                        leaders.put(gangId, uuid);
                        Gang gang = gangs.get(gangId);
                        if (gang != null) gang.leaderUUID = uuid;
                    }
                });
            }
        });

        // Lieutenants
        ctx.storage().getModuleData("gangs", "lieutenants").ifPresent(json -> {
            Map<String, List<String>> map = Json.fromJson(json, new TypeToken<Map<String, List<String>>>() {}.getType());
            if (map != null) {
                map.forEach((gangId, uuidStrs) -> {
                    if (!CANONICAL_IDS.contains(gangId)) return;
                    Set<UUID> set = new java.util.HashSet<>();
                    uuidStrs.forEach(s -> set.add(UUID.fromString(s)));
                    lieutenants.put(gangId, set);
                    Gang gang = gangs.get(gangId);
                    if (gang != null) gang.lieutenantUUIDs.addAll(set);
                });
            }
        });

        // Drugs
        ctx.storage().getModuleData("gangs", "drugs").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, STRING_MAP.getType());
            if (map != null) {
                map.forEach((k, v) -> drugClaims.put(UUID.fromString(k), Long.parseLong(v)));
            }
        });

        // Kill stats - all-time
        ctx.storage().getModuleData("gangs", "killsAllTime").ifPresent(json -> {
            Map<String, Map<String, Integer>> map = Json.fromJson(json, new TypeToken<Map<String, Map<String, Integer>>>() {}.getType());
            if (map != null) {
                map.forEach((gangId, inner) -> {
                    Gang gang = gangs.get(gangId);
                    if (gang == null) return;
                    inner.forEach((uuidStr, count) -> gang.killsAllTime.put(UUID.fromString(uuidStr), count));
                });
            }
        });

        // Kill stats - current term
        ctx.storage().getModuleData("gangs", "killsCurrentTerm").ifPresent(json -> {
            Map<String, Map<String, Integer>> map = Json.fromJson(json, new TypeToken<Map<String, Map<String, Integer>>>() {}.getType());
            if (map != null) {
                map.forEach((gangId, inner) -> {
                    Gang gang = gangs.get(gangId);
                    if (gang == null) return;
                    inner.forEach((uuidStr, count) -> gang.killsThisTerm.put(UUID.fromString(uuidStr), count));
                });
            }
        });

        // Gang banks
        ctx.storage().getModuleData("gangs", "bankBalance").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, STRING_MAP.getType());
            if (map != null) {
                map.forEach((gangId, val) -> {
                    Gang gang = gangs.get(gangId);
                    if (gang != null) gang.bankBalance = Double.parseDouble(val);
                });
            }
        });

        // Votes
        ctx.storage().getModuleData("gangs", "votes").ifPresent(json -> {
            Map<String, Map<String, String>> map = Json.fromJson(json, VOTE_MAP.getType());
            if (map == null) return;
            map.forEach((gang, votesByVoter) -> {
                Map<UUID, UUID> inner = new ConcurrentHashMap<>();
                votesByVoter.forEach((v, c) -> inner.put(UUID.fromString(v), UUID.fromString(c)));
                votes.put(gang, inner);
            });
        });
    }

    private void loadTerritories() {
        ConfigurationSection section = ctx.yaml("gangs.yml").getSection("territories");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            Territory t = new Territory();
            t.id = id;
            t.name = section.getString(id + ".name", id);
            t.regionTag = section.getString(id + ".region-tag", "gangzone:" + id);
            t.captureSeconds = Math.max(10, section.getInt(id + ".capture-seconds", 60));
            t.rewardMoney = section.getDouble(id + ".reward-money", 0);
            t.rewardGuns = section.getInt(id + ".reward-guns", 0);
            territories.put(id, t);
        }
        ctx.storage().getModuleData("gangs", "territories").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, STRING_MAP.getType());
            if (map != null) {
                map.forEach((id, owner) -> {
                    Territory t = territories.get(id);
                    if (t != null && gangs.containsKey(owner)) t.owner = owner;
                });
            }
        });
        ctx.storage().getModuleData("gangs", "territoryProgress").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, STRING_MAP.getType());
            if (map != null) {
                map.forEach((id, val) -> {
                    Territory t = territories.get(id);
                    if (t == null) return;
                    try {
                        String[] parts = val.split(";");
                        t.progress = Integer.parseInt(parts[0]);
                        if (parts.length > 1 && gangs.containsKey(parts[1])) t.progressOwner = parts[1];
                    } catch (NumberFormatException ignored) {
                    }
                });
            }
        });
    }

    // ========================= GANGS =========================

    public Gang gang(String id) {
        return gangs.get(id);
    }

    public List<Gang> all() {
        return new ArrayList<>(gangs.values());
    }

    // ========================= MEMBERSHIPS & BALANCING =========================

    public String gangOf(UUID uuid) {
        for (Gang g : gangs.values()) {
            if (g.memberUUIDs.contains(uuid)) return g.id;
        }
        return null;
    }

    public boolean isMember(UUID uuid, String gangId) {
        Gang g = gangs.get(gangId);
        return g != null && g.memberUUIDs.contains(uuid);
    }

    public List<UUID> membersOf(String gangId) {
        Gang g = gangs.get(gangId);
        return g == null ? List.of() : new ArrayList<>(g.memberUUIDs);
    }

    public int memberCount(String gangId) {
        Gang g = gangs.get(gangId);
        return g == null ? 0 : g.memberUUIDs.size();
    }

    public int memberDiff() {
        return Math.abs(memberCount("north") - memberCount("south"));
    }

    public boolean canJoin(String gangId) {
        int myCount = memberCount(gangId);
        String otherId = "north".equals(gangId) ? "south" : "north";
        int otherCount = memberCount(otherId);
        return (otherCount - myCount) < MAX_MEMBER_DIFF;
    }

    public boolean join(UUID uuid, String gangId) {
        Gang gang = gangs.get(gangId);
        if (gang == null) return false;
        if (!canJoin(gangId)) return false;
        gang.memberUUIDs.add(uuid);
        saveMemberships();
        return true;
    }

    public void leave(UUID uuid) {
        for (Gang gang : gangs.values()) {
            if (gang.memberUUIDs.remove(uuid)) {
                if (uuid.equals(gang.leaderUUID)) {
                    gang.leaderUUID = null;
                    leaders.remove(gang.id);
                }
                gang.lieutenantUUIDs.remove(uuid);
                saveAll();
                return;
            }
        }
    }

    // ========================= HIERARCHY =========================

    public UUID leaderOf(String gangId) {
        Gang g = gangs.get(gangId);
        return g == null ? null : g.leaderUUID;
    }

    public void setLeader(String gangId, UUID uuid) {
        Gang g = gangs.get(gangId);
        if (g == null) return;
        g.leaderUUID = uuid;
        leaders.put(gangId, uuid);
        saveLeaders();
    }

    public Set<UUID> lieutenantsOf(String gangId) {
        Gang g = gangs.get(gangId);
        return g == null ? Set.of() : Set.copyOf(g.lieutenantUUIDs);
    }

    public void promoteToLieutenant(String gangId, UUID uuid) {
        Gang g = gangs.get(gangId);
        if (g == null || !g.memberUUIDs.contains(uuid)) return;
        g.lieutenantUUIDs.add(uuid);
        saveLieutenants();
    }

    public void demoteFromLieutenant(String gangId, UUID uuid) {
        Gang g = gangs.get(gangId);
        if (g == null) return;
        g.lieutenantUUIDs.remove(uuid);
        saveLieutenants();
    }

    public boolean isLeader(UUID uuid, String gangId) {
        return uuid.equals(leaderOf(gangId));
    }

    public boolean isLieutenant(UUID uuid, String gangId) {
        Gang g = gangs.get(gangId);
        return g != null && g.lieutenantUUIDs.contains(uuid);
    }

    public boolean isHigherRank(UUID uuid, String gangId) {
        return isLeader(uuid, gangId) || isLieutenant(uuid, gangId);
    }

    public String rankName(UUID uuid, String gangId) {
        if (isLeader(uuid, gangId)) return "Leader";
        if (isLieutenant(uuid, gangId)) return "Lieutenant";
        return "Member";
    }

    // ========================= KILLS =========================

    public void recordKill(String gangId, UUID killer) {
        Gang g = gangs.get(gangId);
        if (g == null) return;
        g.killsThisTerm.merge(killer, 1, Integer::sum);
        g.killsAllTime.merge(killer, 1, Integer::sum);
        saveKills();
    }

    public int killsThisTerm(String gangId, UUID uuid) {
        Gang g = gangs.get(gangId);
        return g == null ? 0 : g.killsThisTerm.getOrDefault(uuid, 0);
    }

    public int killsAllTime(String gangId, UUID uuid) {
        Gang g = gangs.get(gangId);
        return g == null ? 0 : g.killsAllTime.getOrDefault(uuid, 0);
    }

    /** Sort members of a gang by this-term kills descending. */
    public List<UUID> topKillers(String gangId) {
        Gang g = gangs.get(gangId);
        if (g == null) return List.of();
        return g.memberUUIDs.stream()
                .sorted((a, b) -> Integer.compare(g.killsThisTerm.getOrDefault(b, 0), g.killsThisTerm.getOrDefault(a, 0)))
                .toList();
    }

    public void resetTermKills() {
        for (Gang g : gangs.values()) {
            g.killsThisTerm.clear();
        }
        saveKills();
    }

    // ========================= GANG BANK =========================

    public double bankBalance(String gangId) {
        Gang g = gangs.get(gangId);
        return g == null ? 0 : g.bankBalance;
    }

    public boolean bankDeposit(String gangId, double amount) {
        Gang g = gangs.get(gangId);
        if (g == null || amount <= 0) return false;
        g.bankBalance += amount;
        saveBankBalances();
        return true;
    }

    public boolean bankWithdraw(String gangId, double amount) {
        Gang g = gangs.get(gangId);
        if (g == null || amount <= 0 || g.bankBalance < amount) return false;
        g.bankBalance -= amount;
        saveBankBalances();
        return true;
    }

    // ========================= ELECTIONS (VOTES) =========================

    public void vote(String gangId, UUID voter, UUID candidate) {
        votes.computeIfAbsent(gangId, k -> new ConcurrentHashMap<>()).put(voter, candidate);
        saveVotes();
    }

    public Map<UUID, UUID> votesFor(String gangId) {
        return votes.getOrDefault(gangId, Map.of());
    }

    public int voteCount(String gangId, UUID candidate) {
        return (int) votesFor(gangId).values().stream().filter(c -> c.equals(candidate)).count();
    }

    public void clearVotes() {
        votes.clear();
        saveVotes();
    }

    /** Highest vote count; on tie the incumbent wins. */
    public UUID countWinner(String gangId) {
        Map<UUID, Integer> tally = new HashMap<>();
        votesFor(gangId).values().forEach(c -> tally.merge(c, 1, Integer::sum));
        int best = tally.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (best == 0) return null;
        List<UUID> top = tally.entrySet().stream()
                .filter(e -> e.getValue() == best)
                .map(Map.Entry::getKey)
                .toList();
        if (top.size() == 1) return top.get(0);
        UUID incumbent = leaderOf(gangId);
        return (incumbent != null && top.contains(incumbent)) ? incumbent : top.get(0);
    }

    // ========================= DRUGS =========================

    public int claimDrug(UUID uuid, int limit) {
        long week = java.time.LocalDate.now().toEpochDay() / 7;
        String key = "drugs:" + uuid;
        int used = ctx.storage().getModuleData("gangs", key)
                .filter(v -> v.startsWith(week + ":"))
                .map(v -> Integer.parseInt(v.split(":")[1]))
                .orElse(0);
        if (used >= limit) return -1;
        ctx.storage().setModuleData("gangs", key, week + ":" + (used + 1));
        return used + 1;
    }

    // ========================= TERRITORIES =========================

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
        if (t == null) return;
        t.owner = gangId;
        t.progress = 0;
        t.progressOwner = "";
        saveTerritories();
    }

    public void updateProgress(String id, int progress, String progressOwner) {
        Territory t = territories.get(id);
        if (t == null) return;
        int p = Math.max(0, Math.min(100, progress));
        String po = progressOwner == null ? "" : progressOwner;
        if (t.progress == p && t.progressOwner.equals(po)) return;
        t.progress = p;
        t.progressOwner = po;
        saveTerritories();
    }

    // ========================= WAR STATE =========================

    public void setWarActive(boolean active) {
        gangs.values().forEach(g -> g.warActive = active);
    }

    public boolean isWarActive() {
        return gangs.values().stream().anyMatch(g -> g.warActive);
    }

    // ========================= PERSISTENCE =========================

    private void saveMemberships() {
        Map<String, String> memberMap = new HashMap<>();
        gangs.forEach((id, gang) -> gang.memberUUIDs.forEach(uuid -> memberMap.put(uuid.toString(), id)));
        ctx.storage().setModuleData("gangs", "members", Json.toJson(memberMap));
    }

    private void saveLeaders() {
        Map<String, String> leaderMap = new HashMap<>();
        leaders.forEach((k, v) -> leaderMap.put(k, v.toString()));
        ctx.storage().setModuleData("gangs", "leaders", Json.toJson(leaderMap));
    }

    private void saveLieutenants() {
        Map<String, List<String>> map = new HashMap<>();
        gangs.forEach((id, gang) -> {
            List<String> uuids = gang.lieutenantUUIDs.stream().map(UUID::toString).toList();
            if (!uuids.isEmpty()) map.put(id, uuids);
        });
        ctx.storage().setModuleData("gangs", "lieutenants", Json.toJson(map));
    }

    private void saveKills() {
        Map<String, Map<String, Integer>> allTime = new HashMap<>();
        Map<String, Map<String, Integer>> currentTerm = new HashMap<>();
        gangs.forEach((id, gang) -> {
            Map<String, Integer> at = new HashMap<>();
            gang.killsAllTime.forEach((uuid, count) -> at.put(uuid.toString(), count));
            if (!at.isEmpty()) allTime.put(id, at);
            Map<String, Integer> ct = new HashMap<>();
            gang.killsThisTerm.forEach((uuid, count) -> ct.put(uuid.toString(), count));
            if (!ct.isEmpty()) currentTerm.put(id, ct);
        });
        ctx.storage().setModuleData("gangs", "killsAllTime", Json.toJson(allTime));
        ctx.storage().setModuleData("gangs", "killsCurrentTerm", Json.toJson(currentTerm));
    }

    private void saveBankBalances() {
        Map<String, String> map = new HashMap<>();
        gangs.forEach((id, gang) -> map.put(id, String.valueOf(gang.bankBalance)));
        ctx.storage().setModuleData("gangs", "bankBalance", Json.toJson(map));
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
            if (!t.owner.isEmpty()) owners.put(t.id, t.owner);
            progress.put(t.id, t.progress + (t.progressOwner.isEmpty() ? "" : ";" + t.progressOwner));
        }
        ctx.storage().setModuleData("gangs", "territories", Json.toJson(owners));
        ctx.storage().setModuleData("gangs", "territoryProgress", Json.toJson(progress));
    }

    private void saveAll() {
        saveMemberships();
        saveLeaders();
        saveLieutenants();
        saveKills();
        saveBankBalances();
        saveVotes();
        saveTerritories();
    }
}
