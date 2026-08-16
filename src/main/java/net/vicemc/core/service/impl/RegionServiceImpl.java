package net.vicemc.core.service.impl;

import net.vicemc.api.model.Region;
import net.vicemc.api.service.RegionService;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Region registry persisted to regions.yml.
 */
public final class RegionServiceImpl implements RegionService {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Region> regions = new LinkedHashMap<>();

    public RegionServiceImpl(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "regions.yml");
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            try (InputStream in = plugin.getResource("regions.yml")) {
                if (in != null) {
                    Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ex) {
                plugin.getLogger().warning("Failed to write default regions.yml: " + ex.getMessage());
            }
        }
        load();
    }

    @Override
    public Region create(String id, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Set<String> tags) {
        Region region = new Region(id, world, minX, minY, minZ, maxX, maxY, maxZ, tags);
        regions.put(id, region);
        save();
        return region;
    }

    @Override
    public void delete(String id) {
        regions.remove(id);
        save();
    }

    @Override
    public Optional<Region> byId(String id) {
        return Optional.ofNullable(regions.get(id));
    }

    @Override
    public boolean isInside(Location location, String tag) {
        for (Region region : regions.values()) {
            if (region.hasTag(tag) && region.contains(location)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Set<Region> at(Location location) {
        Set<Region> result = new LinkedHashSet<>();
        for (Region region : regions.values()) {
            if (region.contains(location)) {
                result.add(region);
            }
        }
        return result;
    }

    @Override
    public Set<Region> byTag(String tag) {
        Set<Region> result = new LinkedHashSet<>();
        for (Region region : regions.values()) {
            if (region.hasTag(tag)) {
                result.add(region);
            }
        }
        return result;
    }

    @Override
    public Collection<Region> all() {
        return regions.values();
    }

    public void reload() {
        regions.clear();
        load();
    }

    private void load() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("regions");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            String[] parts = section.getString(key + ".bounds", "").split(":");
            if (parts.length != 3) {
                continue;
            }
            String[] minParts = parts[1].split(",");
            String[] maxParts = parts[2].split(",");
            if (minParts.length != 3 || maxParts.length != 3) {
                continue;
            }
            Set<String> tags = new LinkedHashSet<>(section.getStringList(key + ".tags"));
            try {
                regions.put(key, new Region(key, parts[0],
                        Integer.parseInt(minParts[0]), Integer.parseInt(minParts[1]), Integer.parseInt(minParts[2]),
                        Integer.parseInt(maxParts[0]), Integer.parseInt(maxParts[1]), Integer.parseInt(maxParts[2]),
                        tags));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (Region region : regions.values()) {
            config.set("regions." + region.id() + ".bounds", region.serialized());
            config.set("regions." + region.id() + ".tags", new ArrayList<>(region.tags()));
        }
        try {
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save regions.yml: " + ex.getMessage());
        }
    }
}
