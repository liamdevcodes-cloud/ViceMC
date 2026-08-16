package net.vicemc.api.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Convenience wrapper around a YAML file. If the file does not exist yet and
 * a resource with the same name exists in the plugin jar, it is copied over
 * as the initial config.
 */
public final class YamlConfig {

    private final File file;
    private final InputStream defaults;
    private YamlConfiguration config;

    public YamlConfig(File file, InputStream defaults) {
        this.file = file;
        this.defaults = defaults;
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            if (defaults != null) {
                try {
                    Files.copy(defaults, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ex) {
                    throw new IllegalStateException("Failed to copy default config " + file.getName(), ex);
                }
            }
        }
        reload();
        if (defaults != null) {
            try (InputStreamReader reader = new InputStreamReader(defaults)) {
                YamlConfiguration def = YamlConfiguration.loadConfiguration(reader);
                config.setDefaults(def);
            } catch (IOException ignored) {
            }
        }
    }

    public void reload() {
        config = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        try {
            config.save(file);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to save " + file.getName(), ex);
        }
    }

    public File file() {
        return file;
    }

    public FileConfiguration raw() {
        return config;
    }

    public String getString(String path, String def) {
        return config.getString(path, def);
    }

    public int getInt(String path, int def) {
        return config.getInt(path, def);
    }

    public double getDouble(String path, double def) {
        return config.getDouble(path, def);
    }

    public boolean getBoolean(String path, boolean def) {
        return config.getBoolean(path, def);
    }

    public List<String> getStringList(String path) {
        return config.getStringList(path);
    }

    public ConfigurationSection getSection(String path) {
        return config.getConfigurationSection(path);
    }

    public void set(String path, Object value) {
        config.set(path, value);
    }
}
