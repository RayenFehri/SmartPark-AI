package smartparking.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;

/**
 * Sérialisation JSON centralisée (Gson). Les méthodes de lecture lèvent
 * com.google.gson.JsonSyntaxException si le JSON est invalide : l'appelant
 * (JsonValidator, agents) décide de la réaction.
 */
public final class JsonUtil {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private JsonUtil() { }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static String toPrettyJson(Object value) {
        return PRETTY.toJson(value);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static <T> T fromJson(Reader reader, Class<T> type) {
        return GSON.fromJson(reader, type);
    }
}
