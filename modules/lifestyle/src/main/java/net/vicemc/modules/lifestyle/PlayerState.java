package net.vicemc.modules.lifestyle;

import net.vicemc.api.util.YamlConfig;

import java.util.UUID;

/**
 * Persisted per-player lifestyle stats: rest (sleep bank), thirst and
 * fitness (0..100). Offline time is accounted for by draining since the last
 * update timestamp on load.
 */
public final class PlayerState {

    public UUID uuid;
    public double rest = 100;
    public double thirst = 100;
    public double fitness = 50;
    public long lastUpdate = System.currentTimeMillis();

    public transient boolean warnedRest;
    public transient boolean warnedThirst;
    public transient boolean warnedFitness;
    public transient boolean inBed;
    public transient long bedEnteredAt;

    public PlayerState() {
    }

    public PlayerState(UUID uuid) {
        this.uuid = uuid;
    }

    /** Drains rest and thirst for the given number of real-time seconds. */
    public void drain(double seconds, YamlConfig config) {
        double restHours = config.getDouble("rest.hours", 24);
        double thirstHours = config.getDouble("thirst.hours", 24);
        rest = Math.max(0, rest - seconds * 100.0 / (restHours * 3600));
        thirst = Math.max(0, thirst - seconds * 100.0 / (thirstHours * 3600));
        lastUpdate = System.currentTimeMillis();
    }

    /** Applies rest drain for the time the player was offline. */
    public void drainOffline(YamlConfig config) {
        long now = System.currentTimeMillis();
        if (lastUpdate > 0 && lastUpdate < now) {
            drain((now - lastUpdate) / 1000.0, config);
        } else {
            lastUpdate = now;
        }
    }

    /** Adds sleep bank from time spent in bed (1 real minute = 1 hour of sleep). */
    public void addSleep(double seconds, YamlConfig config) {
        double hours = config.getDouble("rest.hours", 24);
        double gainPerMinute = config.getDouble("rest.sleep-gain-per-minute", 1.0);
        double minutes = seconds / 60.0;
        double bankedHours = minutes * gainPerMinute;
        rest = Math.min(100, rest + (bankedHours / hours) * 100);
        lastUpdate = System.currentTimeMillis();
    }

    public void addFitness(double delta) {
        fitness = clamp(0, 100, fitness + delta);
    }

    public void addThirst(double delta) {
        thirst = clamp(0, 100, thirst + delta);
    }

    public void addRest(double delta) {
        rest = clamp(0, 100, rest + delta);
    }

    /** Walking speed multiplier derived from fitness (0.75x .. 1.5x). */
    public double speedFactor(YamlConfig config) {
        double min = config.getDouble("fitness.min-speed", 0.75);
        double max = config.getDouble("fitness.max-speed", 1.5);
        return min + (fitness / 100.0) * (max - min);
    }

    private static double clamp(double min, double max, double value) {
        return Math.max(min, Math.min(max, value));
    }
}
