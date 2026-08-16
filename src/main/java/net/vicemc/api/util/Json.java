package net.vicemc.api.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Map;

/**
 * Thin Gson wrapper. Gson is provided by the Paper server at runtime.
 */
public final class Json {

    private static final Gson GSON = new GsonBuilder().create();

    private Json() {
    }

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static <T> T fromJson(String json, Type type) {
        return GSON.fromJson(json, type);
    }

    public static Map<String, Object> toMap(Object obj) {
        return GSON.fromJson(GSON.toJson(obj), new TypeToken<Map<String, Object>>() {
        }.getType());
    }
}
