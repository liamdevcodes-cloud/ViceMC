package net.vicemc.core.service.impl;

import net.vicemc.api.service.EventBus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process pub/sub event bus.
 */
public final class EventBusImpl implements EventBus {

    private final Map<String, List<Consumer<Map<String, Object>>>> listeners = new ConcurrentHashMap<>();

    @Override
    public void publish(String channel, Map<String, Object> data) {
        List<Consumer<Map<String, Object>>> list = listeners.get(channel);
        if (list == null) {
            return;
        }
        for (Consumer<Map<String, Object>> listener : list) {
            try {
                listener.accept(data);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void subscribe(String channel, Consumer<Map<String, Object>> listener) {
        listeners.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    public void unsubscribe(String channel, Consumer<Map<String, Object>> listener) {
        List<Consumer<Map<String, Object>>> list = listeners.get(channel);
        if (list != null) {
            list.remove(listener);
        }
    }
}
