package net.vicemc.api.service;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Simple in-process pub/sub bus used for loose coupling between modules
 * (e.g. heists publish "heist.escaped", law module subscribes).
 */
public interface EventBus {

    void publish(String channel, Map<String, Object> data);

    void subscribe(String channel, Consumer<Map<String, Object>> listener);

    void unsubscribe(String channel, Consumer<Map<String, Object>> listener);
}
