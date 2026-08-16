package net.vicemc.modules.farm;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.Json;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Registry of farm regions and per-player farming profiles. Each region is
 * persisted as its own JSON blob under module data (region:<id>) so one region
 * never rewrites the whole set; player profiles are loaded on demand
 * (player:<uuid>). Crop types are read from farming.yml.
 */
public final class FarmManager {

    private static final String REGION_PREFIX = "region:";
    private static final String PLAYER_PREFIX = "player:";
    private static final String NEXT_KEY = "meta:next:region";

    private final ViceModuleContext ctx;
    private final YamlConfig config;
    private final Map<String, CropType> crops = new LinkedHashMap<>();
    private final Map<String, FarmRegion> regions = new LinkedHashMap<>();

    public FarmManager(ViceModuleContext ctx, YamlConfig config) {
        this.ctx = ctx;
        this.config = config;
        loadCrops();
        loadRegions();
    }

    // --- Crop types -------------------------------------------------------

    private void loadCrops() {
        ConfigurationSection section = config.getSection("crops");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection c = section.getConfigurationSection(id);
            if (c == null) {
                continue;
            }
            Material block = Material.matchMaterial(c.getString("block", id));
            Material item = Material.matchMaterial(c.getString("item", id));
            if (block == null || item == null) {
                continue;
            }
            double value = c.getDouble("value", 1);
            double xp = c.getDouble("xp", value);
            int level = Math.max(1, c.getInt("level", 1));
            int regen = Math.max(5, c.getInt("regen-seconds", 45));
            crops.put(id.toUpperCase(), new CropType(id.toUpperCase(), block, item,
                    value, xp, level, regen));
        }
    }

    public Map<String, CropType> crops() {
        return crops;
    }

    public List<String> cropIds() {
        return new ArrayList<>(crops.keySet());
    }

    public CropType cropById(String id) {
        return id == null ? null : crops.get(id.toUpperCase());
    }

    public CropType cropFor(FarmRegion region) {
        return region == null ? null : cropById(region.crop);
    }

    public CropType wandCrop() {
        CropType crop = cropById(config.getString("wand.crop", "WHEAT"));
        if (crop != null) {
            return crop;
        }
        for (CropType c : crops.values()) {
            return c;
        }
        return null;
    }

    public void setWandCrop(String id) {
        config.set("wand.crop", id.toUpperCase());
        config.save();
    }

    // --- Margins ----------------------------------------------------------

    public double defaultMargin() {
        return clampMargin(config.getDouble("default-margin", 0.02));
    }

    public double maxMargin() {
        return Math.max(0.01, config.getDouble("max-margin", 0.10));
    }

    public double clampMargin(double value) {
        return Math.max(0.01, Math.min(maxMargin(), value));
    }

    // --- Persistence ------------------------------------------------------

    private void loadRegions() {
        for (Map.Entry<String, String> entry : ctx.storage().moduleDataAll("farm").entrySet()) {
            if (!entry.getKey().startsWith(REGION_PREFIX)) {
                continue;
            }
            try {
                FarmRegion region = Json.fromJson(entry.getValue(), FarmRegion.class);
                if (region != null && region.id != null && !region.id.isEmpty()) {
                    regions.put(region.id, region);
                }
            } catch (Exception ignored) {
            }
        }
    }

    public void save(FarmRegion region) {
        ctx.storage().setModuleData("farm", REGION_PREFIX + region.id, Json.toJson(region));
    }

    public void deleteRegion(FarmRegion region) {
        regions.remove(region.id);
        ctx.storage().removeModuleData("farm", REGION_PREFIX + region.id);
    }

    // --- Region creation --------------------------------------------------

    private String nextRegionId() {
        int counter;
        try {
            counter = ctx.storage().getModuleData("farm", NEXT_KEY).map(Integer::parseInt).orElse(0) + 1;
        } catch (NumberFormatException ex) {
            counter = 1;
        }
        ctx.storage().setModuleData("farm", NEXT_KEY, String.valueOf(counter));
        return String.format("F%03d", counter);
    }

    public FarmRegion createRegion(CropType crop, Location a, Location b) {
        FarmRegion region = new FarmRegion();
        region.id = nextRegionId();
        region.crop = crop.id;
        region.world = a.getWorld().getName();
        region.minX = Math.min(a.getBlockX(), b.getBlockX());
        region.maxX = Math.max(a.getBlockX(), b.getBlockX());
        region.minY = Math.min(a.getBlockY(), b.getBlockY());
        region.maxY = Math.max(a.getBlockY(), b.getBlockY());
        region.minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        region.maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
        region.owner = null;
        region.margin = defaultMargin();
        if (overlaps(region)) {
            return null;
        }
        regions.put(region.id, region);
        save(region);
        return region;
    }

    public boolean overlaps(FarmRegion candidate) {
        for (FarmRegion other : regions.values()) {
            if (other.id.equals(candidate.id)) {
                continue;
            }
            if (!other.world.equalsIgnoreCase(candidate.world)) {
                continue;
            }
            if (candidate.minX <= other.maxX && candidate.maxX >= other.minX
                    && candidate.minY <= other.maxY && candidate.maxY >= other.minY
                    && candidate.minZ <= other.maxZ && candidate.maxZ >= other.minZ) {
                return true;
            }
        }
        return false;
    }

    // --- Region queries ---------------------------------------------------

    public FarmRegion byId(String id) {
        return id == null ? null : regions.get(id.toUpperCase());
    }

    public List<String> regionIds() {
        List<String> ids = new ArrayList<>(regions.keySet());
        ids.sort(String::compareTo);
        return ids;
    }

    public List<FarmRegion> all() {
        List<FarmRegion> list = new ArrayList<>(regions.values());
        list.sort((r1, r2) -> r1.id.compareTo(r2.id));
        return list;
    }

    public List<FarmRegion> regionsOwnedBy(UUID owner) {
        List<FarmRegion> list = new ArrayList<>();
        String id = owner.toString();
        for (FarmRegion region : regions.values()) {
            if (id.equals(region.owner)) {
                list.add(region);
            }
        }
        list.sort((r1, r2) -> r1.id.compareTo(r2.id));
        return list;
    }

    public FarmRegion at(Block block) {
        for (FarmRegion region : regions.values()) {
            if (region.contains(block)) {
                return region;
            }
        }
        return null;
    }

    public void setOwner(FarmRegion region, UUID uuid) {
        region.owner = uuid == null ? null : uuid.toString();
        save(region);
    }

    public void setMargin(FarmRegion region, double margin) {
        region.margin = clampMargin(margin);
        save(region);
    }

    // --- Player profiles --------------------------------------------------

    public FarmProfile profile(UUID uuid) {
        Optional<String> blob = ctx.storage().getModuleData("farm", PLAYER_PREFIX + uuid);
        if (blob.isPresent()) {
            try {
                FarmProfile profile = Json.fromJson(blob.get(), FarmProfile.class);
                if (profile != null) {
                    if (profile.crops == null) {
                        profile.crops = new ArrayList<>();
                    }
                    return profile;
                }
            } catch (Exception ignored) {
            }
        }
        return new FarmProfile();
    }

    public void saveProfile(UUID uuid, FarmProfile profile) {
        ctx.storage().setModuleData("farm", PLAYER_PREFIX + uuid, Json.toJson(profile));
    }

    public int level(UUID uuid) {
        return profile(uuid).level;
    }

    public void setLevel(UUID uuid, int level) {
        FarmProfile profile = profile(uuid);
        profile.level = Math.max(1, level);
        profile.xp = 0;
        saveProfile(uuid, profile);
    }

    public long xpNeeded(int level) {
        return (long) (config.getDouble("levels.xp-per-level", 100) * Math.max(1, level));
    }

    // --- Harvesting -------------------------------------------------------

    public boolean canHarvest(Player player, CropType crop) {
        return profile(player.getUniqueId()).level >= crop.level;
    }

    public boolean isMature(Block block, CropType crop) {
        if (block.getType() != crop.block) {
            return false;
        }
        BlockData data = block.getBlockData();
        return data instanceof Ageable ageable && ageable.getAge() >= ageable.getMaximumAge();
    }

    /** Sets the crop back to a fresh seedling so it visibly regrows. */
    public void resetCrop(Block block, CropType crop) {
        if (block.getType() != crop.block) {
            return;
        }
        BlockData data = block.getBlockData();
        if (data instanceof Ageable ageable) {
            ageable.setAge(0);
            block.setBlockData(data);
        }
    }

    /**
     * Records the harvest into the player's /farm inventory with its farm
     * provenance, grants XP (with level ups) and pays nothing yet - the money
     * only moves when the crops are sold to the government.
     */
    public void harvest(Player player, FarmRegion region, CropType crop) {
        FarmProfile profile = profile(player.getUniqueId());

        boolean taxed = !region.isPublic()
                && !region.owner.equals(player.getUniqueId().toString());
        int taxPercent = taxed ? (int) Math.round(clampMargin(region.margin) * 100) : 0;
        String ownerId = taxed ? region.owner : "";
        String ownerName = taxed ? nameOf(region.owner) : "";

        StoredCrop stored = findStored(profile, crop.id, region.id, ownerId, taxPercent);
        if (stored == null) {
            stored = new StoredCrop();
            stored.crop = crop.id;
            stored.farmId = region.id;
            stored.farmOwnerId = ownerId;
            stored.farmOwnerName = ownerName;
            stored.taxPercent = taxPercent;
            profile.crops.add(stored);
        }
        stored.amount += 1;

        profile.xp += crop.xp;
        int before = profile.level;
        while (profile.xp >= xpNeeded(profile.level)) {
            profile.xp -= xpNeeded(profile.level);
            profile.level += 1;
        }
        saveProfile(player.getUniqueId(), profile);

        if (profile.level > before) {
            ctx.notifications().msg(player, "&6&lFARM LEVEL UP! &6You are now Farm Level &f"
                    + profile.level + "&6.");
        }
        ctx.events().publish("farm.harvest", Map.of(
                "player", player.getName(), "crop", crop.id, "farm", region.id, "value", crop.value));
    }

    private StoredCrop findStored(FarmProfile profile, String crop, String farmId,
                                  String ownerId, int taxPercent) {
        for (StoredCrop stored : profile.crops) {
            if (stored.crop.equalsIgnoreCase(crop)
                    && stored.farmId.equals(farmId)
                    && stored.farmOwnerId.equals(ownerId)
                    && stored.taxPercent == taxPercent) {
                return stored;
            }
        }
        return null;
    }

    // --- Name helpers -----------------------------------------------------

    public String nameOf(String uuidString) {
        if (uuidString == null) {
            return "unknown";
        }
        try {
            return nameOf(UUID.fromString(uuidString));
        } catch (IllegalArgumentException ex) {
            return uuidString;
        }
    }

    public String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    public UUID uuidByName(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) {
            return exact.getUniqueId();
        }
        Player partial = Bukkit.getPlayer(name);
        if (partial != null) {
            return partial.getUniqueId();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline.getUniqueId() : null;
    }
}
