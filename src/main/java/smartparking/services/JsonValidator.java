package smartparking.services;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import smartparking.models.ParkingRequest;
import smartparking.models.PreferredLocation;
import smartparking.models.Priority;
import smartparking.models.VehicleType;

/**
 * Valide et convertit la sortie JSON d'un LLM en ParkingRequest.
 * Tolère le bruit courant (clôtures ```json, texte avant/après l'objet) mais
 * rejette tout contenu hors contrat : champ manquant, type erroné, valeur hors bornes
 * ou combinaison incohérente.
 */
public final class JsonValidator {

    public static final double MAX_DURATION_H = 48.0;
    public static final double MAX_BUDGET_DT = 10_000.0;

    private JsonValidator() { }

    public static ParkingRequest validate(String raw) throws NlParseException {
        String json = extractJson(raw);
        JsonObject o;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                throw new NlParseException("JSON invalide : un objet est attendu");
            }
            o = root.getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            throw new NlParseException("JSON invalide : " + e.getMessage(), e);
        }

        double duration = number(o, "duration", true, 0);
        double budget = number(o, "maxBudget", true, 0);
        if (duration <= 0 || duration > MAX_DURATION_H) {
            throw new NlParseException("duration hors bornes (0, " + MAX_DURATION_H + "] : " + duration);
        }
        if (budget < 0 || budget > MAX_BUDGET_DT) {
            throw new NlParseException("maxBudget hors bornes [0, " + MAX_BUDGET_DT + "] : " + budget);
        }
        VehicleType type = enumValue(o, "vehicleType", VehicleType.class, true, null);
        boolean charging = bool(o, "requiresCharging");
        boolean pmr = bool(o, "requiresPmr");
        Priority priority = enumValue(o, "priority", Priority.class, false, Priority.NORMAL);
        PreferredLocation loc = enumValue(o, "preferredLocation", PreferredLocation.class, false,
                PreferredLocation.ANY);
        long arrival = (long) number(o, "arrivalSimMin", false, 0);
        if (arrival < 0) {
            throw new NlParseException("arrivalSimMin négatif : " + arrival);
        }
        if (charging && pmr) {
            throw new NlParseException("requiresCharging et requiresPmr ne peuvent pas être vrais ensemble");
        }
        if (charging && type == VehicleType.PMR || pmr && type == VehicleType.ELECTRIC) {
            throw new NlParseException("vehicleType incohérent avec les besoins déclarés");
        }
        return new ParkingRequest(null, null, duration, budget, type, charging, pmr, priority, loc, arrival);
    }

    /** Isole l'objet JSON : retire les clôtures Markdown et le texte autour des accolades. */
    static String extractJson(String raw) throws NlParseException {
        if (raw == null || raw.isBlank()) {
            throw new NlParseException("réponse vide");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new NlParseException("aucun objet JSON dans la réponse");
        }
        return raw.substring(start, end + 1);
    }

    private static double number(JsonObject o, String key, boolean required, double def)
            throws NlParseException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            if (required) {
                throw new NlParseException("champ obligatoire manquant : " + key);
            }
            return def;
        }
        try {
            if (e.isJsonPrimitive()) {
                double v = e.getAsDouble();
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    throw new NlParseException("nombre invalide pour " + key);
                }
                return v;
            }
        } catch (NumberFormatException ex) {
            // traité ci-dessous
        }
        throw new NlParseException("nombre attendu pour " + key);
    }

    private static boolean bool(JsonObject o, String key) throws NlParseException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return false;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        throw new NlParseException("booléen attendu pour " + key);
    }

    private static <E extends Enum<E>> E enumValue(JsonObject o, String key, Class<E> type,
                                                   boolean required, E def) throws NlParseException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            if (required) {
                throw new NlParseException("champ obligatoire manquant : " + key);
            }
            return def;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new NlParseException("chaîne attendue pour " + key);
        }
        try {
            return Enum.valueOf(type, e.getAsString().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new NlParseException("valeur inconnue pour " + key + " : " + e.getAsString());
        }
    }
}