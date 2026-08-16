package net.vicemc.api.service;

import java.util.Map;
import java.util.Optional;

/**
 * Persistent key/value storage backed by SQLite. Modules use the moduleData
 * methods to persist their own blobs (typically JSON).
 */
public interface StorageService {

    void put(String key, String value);

    Optional<String> get(String key);

    void remove(String key);

    Map<String, String> all(String keyPrefix);

    void setModuleData(String moduleId, String key, String value);

    Optional<String> getModuleData(String moduleId, String key);

    void removeModuleData(String moduleId, String key);

    Map<String, String> moduleDataAll(String moduleId);
}
