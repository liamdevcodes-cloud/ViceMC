package net.vicemc.core.service.impl;

import net.vicemc.api.service.StorageService;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * SQLite persistence for accounts, transactions, generic kv and module data.
 */
public final class StorageServiceImpl implements StorageService {

    private final JavaPlugin plugin;
    private Connection connection;

    public StorageServiceImpl(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.getDataFolder().mkdirs();
        File dbFile = new File(plugin.getDataFolder(), "vicemc.db");
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            initTables();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to open SQLite database", ex);
        }
    }

    private void initTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS accounts (uuid TEXT PRIMARY KEY, balance REAL NOT NULL, updated_at INTEGER NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, amount REAL NOT NULL, category TEXT, at INTEGER NOT NULL, balance_after REAL NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS module_data (module TEXT NOT NULL, k TEXT NOT NULL, v TEXT NOT NULL, PRIMARY KEY (module, k))");
        }
    }

    public Connection connection() {
        return connection;
    }

    @Override
    public synchronized void put(String key, String value) {
        try (PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO kv (k, v) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log(ex);
        }
    }

    @Override
    public synchronized Optional<String> get(String key) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT v FROM kv WHERE k = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException ex) {
            log(ex);
            return Optional.empty();
        }
    }

    @Override
    public synchronized void remove(String key) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM kv WHERE k = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log(ex);
        }
    }

    @Override
    public synchronized Map<String, String> all(String keyPrefix) {
        Map<String, String> result = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT k, v FROM kv WHERE k LIKE ?")) {
            ps.setString(1, keyPrefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getString(2));
                }
            }
        } catch (SQLException ex) {
            log(ex);
        }
        return result;
    }

    @Override
    public synchronized void setModuleData(String moduleId, String key, String value) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO module_data (module, k, v) VALUES (?, ?, ?)")) {
            ps.setString(1, moduleId);
            ps.setString(2, key);
            ps.setString(3, value);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log(ex);
        }
    }

    @Override
    public synchronized Optional<String> getModuleData(String moduleId, String key) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT v FROM module_data WHERE module = ? AND k = ?")) {
            ps.setString(1, moduleId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException ex) {
            log(ex);
            return Optional.empty();
        }
    }

    @Override
    public synchronized void removeModuleData(String moduleId, String key) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM module_data WHERE module = ? AND k = ?")) {
            ps.setString(1, moduleId);
            ps.setString(2, key);
            ps.executeUpdate();
        } catch (SQLException ex) {
            log(ex);
        }
    }

    @Override
    public synchronized Map<String, String> moduleDataAll(String moduleId) {
        Map<String, String> result = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT k, v FROM module_data WHERE module = ?")) {
            ps.setString(1, moduleId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getString(2));
                }
            }
        } catch (SQLException ex) {
            log(ex);
        }
        return result;
    }

    public void close() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ignored) {
        }
    }

    private void log(SQLException ex) {
        plugin.getLogger().severe("Storage error: " + ex.getMessage());
    }
}
