package net.vicemc.modules.gangs;

import com.google.gson.reflect.TypeToken;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.ItemBuilder;
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
 * Gang data, memberships, leaders, lieutenants, kills, bank, votes, territories.
 * All runtime state; config-defined territory/gang data is reloaded on demand.
 */
public final class GangManager {

    public static final Set<String> CANONICAL_IDS = Set.of("north", "south");

    private final ViceModuleContext ctx;
    private final Map<String, Gang> gangs = new LinkedHashMap<>();
    private final Map<String, UUID> leaders = new ConcurrentHashMap<>();
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
            ConfigurationSection gSection = section.getConfigurationSection(id);
            if (gSection == null) continue;
            Gang gang = new Gang();
            gang.id = id;
            gang.name = gSection.getString("name", id);
            gang.regionTag = gSection.getString("region-tag", "gang:" + id);
            gang.drugMaterial = gSection.getString("drug-material", "SUGAR");
            gang.drugName = gSection.getString("drug-name", "Drugs");
            gang.drugEffect = gSection.getString("drug-effect", "HASTE");
            gang.drugAmplifier = gSection.getInt("drug-amplifier", 1);
            gang.drugDurationSeconds = gSection.getInt("drug-duration-seconds", 60);
            gangs.put(id, gang);
        }
    }

    // ========================= PERSISTENCE LOAD =========================

    private void loadGangData() {
        ctx.storage().getModuleData("gangs", "members").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, new TypeToken<Map<String, String>>() {}.getType());
            if (map != null) map.forEach((k, v) -> {
                UUID uuid = UUID.fromString(k);
                Gang g = gangs.get(v);
                if (g != null) g.memberUUIDs.add(uuid);
            });
        });

        ctx.storage().getModuleData("gangs", "leaders").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, new TypeToken<Map<String, String>>() {}.getType());
            if (map != null) map.forEach((gangId, uuidStr) -> {
                UUID uuid = UUID.fromString(uuidStr);
                leaders.put(gangId, uuid);
                Gang g = gangs.get(gangId);
                if (g != null) g.leaderUUID = uuid;
            });
        });

        ctx.storage().getModuleData("gangs", "lieutenants").ifPresent(json -> {
            Map<String, List<String>> map = Json.fromJson(json, new TypeToken<Map<String, List<String>>>() {}.getType());
            if (map != null) map.forEach((gangId, uuidStrs) -> {
                Gang g = gangs.get(gangId);
                if (g == null) return;
                uuidStrs.forEach(s -> g.lieutenantUUIDs.add(UUID.fromString(s)));
            });
        });

        ctx.storage().getModuleData("gangs", "drugs").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, new TypeToken<Map<String, String>>() {}.getType());
            if (map != null) map.forEach((k, v) -> drugClaims.put(UUID.fromString(k), Long.parseLong(v)));
        });

        ctx.storage().getModuleData("gangs", "killsAllTime").ifPresent(json -> {
            Map<String, Map<String, Integer>> map = Json.fromJson(json, new TypeToken<Map<String, Map<String, Integer>>>() {}.getType());
            if (map != null) map.forEach((gangId, inner) -> {
                Gang g = gangs.get(gangId);
                if (g != null) inner.forEach((s, c) -> g.killsAllTime.put(UUID.fromString(s), c));
            });
        });

        ctx.storage().getModuleData("gangs", "killsCurrentTerm").ifPresent(json -> {
            Map<String, Map<String, Integer>> map = Json.fromJson(json, new TypeToken<Map<String, Map<String, Integer>>>() {}.getType());
            if (map != null) map.forEach((gangId, inner) -> {
                Gang g = gangs.get(gangId);
                if (g != null) inner.forEach((s, c) -> g.killsThisTerm.put(UUID.fromString(s), c));
            });
        });

        ctx.storage().getModuleData("gangs", "bankBalance").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, new TypeToken<Map<String, String>>() {}.getType());
            if (map != null) map.forEach((gangId, val) -> {
                Gang g = gangs.get(gangId);
                if (g != null) g.bankBalance = Double.parseDouble(val);
            });
        });

        ctx.storage().getModuleData("gangs", "votes").ifPresent(json -> {
            Map<String, Map<String, String>> map = Json.fromJson(json, new TypeToken<Map<String, Map<String, String>>>() {}.getType());
            if (map != null) map.forEach((gang, votesByVoter) -> {
                Map<UUID, UUID> inner = new ConcurrentHashMap<>();
                votesByVoter.forEach((v, c) -> inner.put(UUID.fromString(v), UUID.fromString(c)));
                votes.put(gang, inner);
            });
        });

        // Gang region overrides (admin-set region tags)
        ctx.storage().getModuleData("gangs", "gangRegions").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, new TypeToken<Map<String, String>>() {}.getType());
            if (map != null) map.forEach((gangId, tag) -> {
                Gang g = gangs.get(gangId);
                if (g != null) g.regionTag = tag;
            });
        });
    }

    private void loadTerritories() {
        territories.clear();
        var cfg = ctx.yaml("gangs.yml");
        ConfigurationSection section = cfg.getSection("territories");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            Territory t = new Territory();
            t.id = id;
            t.name = cfg.getString("territories." + id + ".name", id);
            t.regionTag = cfg.getString("territories." + id + ".region-tag", "gangzone:" + id);
            t.captureSeconds = Math.max(10, cfg.getInt("territories." + id + ".capture-seconds", 60));
            t.rewardMoney = cfg.getDouble("territories." + id + ".reward-money", 0);
            t.weeklyRewardMoney = cfg.getDouble("territories." + id + ".weekly-reward-money", 0);

            // Load configurable reward items
            ConfigurationSection itemsSection = cfg.getSection("territories." + id + ".reward-items");
            if (itemsSection != null) {
                for (String key : itemsSection.getKeys(false)) {
                    String path = "territories." + id + ".reward-items." + key;
                    String matName = cfg.getString(path + ".material", "PAPER");
                    String itemName = cfg.getString(path + ".name", key);
                    int amount = cfg.getInt(path + ".amount", 1);
                    List<String> lore = cfg.getStringList(path + ".lore");

                    org.bukkit.Material mat = org.bukkit.Material.matchMaterial(matName);
                    if (mat == null) mat = org.bukkit.Material.PAPER;
                    ItemStack item = ItemBuilder.of(mat).name(itemName).lore(lore.toArray(new String[0])).build();
                    t.rewardItems.add(item);
                }
            }
            territories.put(id, t);
        }

        // Restore owners and progress from storage
        ctx.storage().getModuleData("gangs", "territories").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, new TypeToken<Map<String, String>>() {}.getType());
            if (map != null) map.forEach((id, owner) -> {
                Territory t = territories.get(id);
                if (t != null && gangs.containsKey(owner)) t.owner = owner;
            });
        });
        ctx.storage().getModuleData("gangs", "territoryProgress").ifPresent(json -> {
            Map<String, String> map = Json.fromJson(json, new TypeToken<Map<String, String>>() {}.getType());
            if (map != null) map.forEach((id, val) -> {
                Territory t = territories.get(id);
                if (t == null) return;
                try {
                    String[] parts = val.split(";");
                    t.progress = Integer.parseInt(parts[0]);
                    if (parts.length > 1 && gangs.containsKey(parts[1])) t.progressOwner = parts[1];
                } catch (NumberFormatException ignored) {}
            });
        });
    }

    // ========================= ADMIN: RELOAD =========================

    public void reload() {
        gangs.clear();
        territories.clear();
        leaders.clear();
        votes.clear();
        drugClaims.clear();
        loadConfig();
        loadGangData();
        loadTerritories();
    }

    // ========================= GANGS =========================

    public Gang gang(String id) { return gangs.get(id); }
    public List<Gang> all() { return new ArrayList<>(gangs.values()); }

    // ========================= MEMBERSHIPS & BALANCING =========================

    public String gangOf(UUID uuid) {
        for (Gang g : gangs.values()) if (g.memberUUIDs.contains(uuid)) return g.id;
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
        return (memberCount(otherId) - myCount) < 4;
    }

    /** Join bypassing balance check (admin use). */
    public boolean joinForce(UUID uuid, String gangId) {
        Gang g = gangs.get(gangId);
        if (g == null) return false;
        g.memberUUIDs.add(uuid);
        saveMemberships();
        return true;
    }

    public boolean join(UUID uuid, String gangId) {
        if (!canJoin(gangId)) return false;
        return joinForce(uuid, gangId);
    }

    public void leave(UUID uuid) {
        for (Gang g : gangs.values()) {
            if (g.memberUUIDs.remove(uuid)) {
                if (uuid.equals(g.leaderUUID)) { g.leaderUUID = null; leaders.remove(g.id); }
                g.lieutenantUUIDs.remove(uuid);
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
        if (g != null) g.lieutenantUUIDs.remove(uuid);
        saveLieutenants();
    }

    public boolean isLeader(UUID uuid, String gangId) { return uuid.equals(leaderOf(gangId)); }
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

    // ========================= GANG REGION =========================

    /** Get the region tag assigned to a gang (admin override or config default). */
    public String gangRegionTag(String gangId) {
        Gang g = gangs.get(gangId);
        return g == null ? "" : g.regionTag;
    }

    /** Set a gang's region tag (admin override). Persists to storage. */
    public boolean setGangRegion(String gangId, String regionTag) {
        Gang g = gangs.get(gangId);
        if (g == null) return false;
        g.regionTag = regionTag;
        saveGangRegions();
        return true;
    }

    private void saveGangRegions() {
        Map<String, String> map = new HashMap<>();
        gangs.forEach((id, g) -> map.put(id, g.regionTag));
        ctx.storage().setModuleData("gangs", "gangRegions", Json.toJson(map));
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

    public List<UUID> topKillers(String gangId) {
        Gang g = gangs.get(gangId);
        if (g == null) return List.of();
        return g.memberUUIDs.stream()
                .sorted((a, b) -> Integer.compare(g.killsThisTerm.getOrDefault(b, 0), g.killsThisTerm.getOrDefault(a, 0)))
                .toList();
    }

    public void resetTermKills() {
        gangs.values().forEach(g -> g.killsThisTerm.clear());
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

    // ========================= ELECTIONS =========================

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

    public void clearVotes() { votes.clear(); saveVotes(); }

    public UUID countWinner(String gangId) {
        Map<UUID, Integer> tally = new HashMap<>();
        votesFor(gangId).values().forEach(c -> tally.merge(c, 1, Integer::sum));
        int best = tally.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (best == 0) return null;
        List<UUID> top = tally.entrySet().stream().filter(e -> e.getValue() == best).map(Map.Entry::getKey).toList();
        if (top.size() == 1) return top.get(0);
        UUID inc = leaderOf(gangId);
        return (inc != null && top.contains(inc)) ? inc : top.get(0);
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

    public List<Territory> territories() { return new ArrayList<>(territories.values()); }
    public Territory territory(String id) { return territories.get(id); }

    public int territoriesControlled(String gangId) {
        return (int) territories.values().stream().filter(t -> gangId.equals(t.owner)).count();
    }

    public boolean controlsAny(String gangId) { return territoriesControlled(gangId) > 0; }

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
        t.progress = Math.max(0, Math.min(100, progress));
        t.progressOwner = progressOwner == null ? "" : progressOwner;
        saveTerritories();
    }

    // ========================= ADMIN: TERRITORY MANAGEMENT =========================

    /** Create a new territory at runtime from an admin command. */
    public Territory createTerritory(String id, String name, String regionTag,
                                     int captureSeconds, double rewardMoney, double weeklyRewardMoney) {
        if (territories.containsKey(id)) return null;
        Territory t = Territory.create(id, name, regionTag, captureSeconds, rewardMoney, weeklyRewardMoney);
        territories.put(id, t);
        saveTerritories();
        return t;
    }

    /** Change a territory's region tag at runtime. */
    public boolean setTerritoryRegion(String id, String regionTag) {
        Territory t = territories.get(id);
        if (t == null) return false;
        t.regionTag = regionTag;
        saveTerritories();
        return true;
    }

    /** Set a territory's capture seconds at runtime. */
    public boolean setTerritoryCaptureTime(String id, int seconds) {
        Territory t = territories.get(id);
        if (t == null) return false;
        t.captureSeconds = Math.max(10, seconds);
        saveTerritories();
        return true;
    }

    /** Set a territory's reward money at runtime. */
    public boolean setTerritoryRewardMoney(String id, double money) {
        Territory t = territories.get(id);
        if (t == null) return false;
        t.rewardMoney = money;
        saveTerritories();
        return true;
    }

    /** Set a territory's weekly holding reward at runtime. */
    public boolean setTerritoryWeeklyReward(String id, double money) {
        Territory t = territories.get(id);
        if (t == null) return false;
        t.weeklyRewardMoney = money;
        saveTerritories();
        return true;
    }

    /** Add a reward item to a territory. */
    public boolean addTerritoryRewardItem(String id, ItemStack item) {
        Territory t = territories.get(id);
        if (t == null) return false;
        t.rewardItems.add(item);
        saveTerritoryRewardItems(t);
        return true;
    }

    /** Clear all reward items from a territory. */
    public boolean clearTerritoryRewardItems(String id) {
        Territory t = territories.get(id);
        if (t == null) return false;
        t.rewardItems.clear();
        saveTerritoryRewardItems(t);
        return true;
    }

    private void saveTerritoryRewardItems(Territory t) {
        // Persist as JSON list of item descriptions
        List<String> itemDescs = new ArrayList<>();
        for (ItemStack item : t.rewardItems) {
            String mat = item.getType().name();
            String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                    ? net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(item.getItemMeta().displayName()) : mat;
            int amount = item.getAmount();
            itemDescs.add(mat + "|" + name + "|" + amount);
        }
        ctx.storage().setModuleData("gangs", "territoryItems:" + t.id, String.join(";;", itemDescs));
    }

    /** Delete a territory by id. */
    public boolean deleteTerritory(String id) {
        Territory removed = territories.remove(id);
        if (removed == null) return false;
        saveTerritories();
        return true;
    }

    // ========================= ADMIN: GANG MEMBER MANAGEMENT =========================

    /** Force-join a player to a gang, bypassing balance. */
    public boolean adminForceJoin(UUID uuid, String gangId) {
        Gang g = gangs.get(gangId);
        if (g == null) return false;
        g.memberUUIDs.add(uuid);
        saveMemberships();
        return true;
    }

    /** Force-leave a player from whatever gang they're in. */
    public boolean adminForceLeave(UUID uuid) {
        for (Gang g : gangs.values()) {
            if (g.memberUUIDs.remove(uuid)) {
                if (uuid.equals(g.leaderUUID)) { g.leaderUUID = null; leaders.remove(g.id); }
                g.lieutenantUUIDs.remove(uuid);
                saveAll();
                return true;
            }
        }
        return false;
    }

    // ========================= WAR STATE =========================

    public void setWarActive(boolean active) { gangs.values().forEach(g -> g.warActive = active); }
    public boolean isWarActive() { return gangs.values().stream().anyMatch(g -> g.warActive); }

    // ========================= WEEKLY HOLDING REWARDS =========================

    /** Distribute weekly holding rewards to all gangs that own territories. */
    public Map<String, Double> distributeWeeklyRewards() {
        Map<String, Double> distributed = new HashMap<>();
        for (Territory t : territories.values()) {
            if (!t.owner.isEmpty() && t.weeklyRewardMoney > 0) {
                bankDeposit(t.owner, t.weeklyRewardMoney);
                distributed.merge(t.owner, t.weeklyRewardMoney, Double::sum);
            }
        }
        return distributed;
    }

    // ========================= PERSISTENCE =========================

    private void saveMemberships() {
        Map<String, String> m = new HashMap<>();
        gangs.forEach((id, g) -> g.memberUUIDs.forEach(u -> m.put(u.toString(), id)));
        ctx.storage().setModuleData("gangs", "members", Json.toJson(m));
    }

    private void saveLeaders() {
        Map<String, String> m = new HashMap<>();
        leaders.forEach((k, v) -> m.put(k, v.toString()));
        ctx.storage().setModuleData("gangs", "leaders", Json.toJson(m));
    }

    private void saveLieutenants() {
        Map<String, List<String>> m = new HashMap<>();
        gangs.forEach((id, g) -> {
            List<String> l = g.lieutenantUUIDs.stream().map(UUID::toString).toList();
            if (!l.isEmpty()) m.put(id, l);
        });
        ctx.storage().setModuleData("gangs", "lieutenants", Json.toJson(m));
    }

    private void saveKills() {
        Map<String, Map<String, Integer>> at = new HashMap<>();
        Map<String, Map<String, Integer>> ct = new HashMap<>();
        gangs.forEach((id, g) -> {
            Map<String, Integer> atM = new HashMap<>();
            g.killsAllTime.forEach((u, c) -> atM.put(u.toString(), c));
            if (!atM.isEmpty()) at.put(id, atM);
            Map<String, Integer> ctM = new HashMap<>();
            g.killsThisTerm.forEach((u, c) -> ctM.put(u.toString(), c));
            if (!ctM.isEmpty()) ct.put(id, ctM);
        });
        ctx.storage().setModuleData("gangs", "killsAllTime", Json.toJson(at));
        ctx.storage().setModuleData("gangs", "killsCurrentTerm", Json.toJson(ct));
    }

    private void saveBankBalances() {
        Map<String, String> m = new HashMap<>();
        gangs.forEach((id, g) -> m.put(id, String.valueOf(g.bankBalance)));
        ctx.storage().setModuleData("gangs", "bankBalance", Json.toJson(m));
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
        saveMemberships(); saveLeaders(); saveLieutenants();
        saveKills(); saveBankBalances(); saveVotes(); saveTerritories();
    }
}
