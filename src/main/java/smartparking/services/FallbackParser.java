package smartparking.services;

import smartparking.models.ParkingRequest;
import smartparking.models.PreferredLocation;
import smartparking.models.Priority;
import smartparking.models.VehicleType;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parseur à base de règles (français/anglais), utilisé sans clé API ou quand le LLM
 * échoue. Déterministe, sans réseau. La durée est obligatoire ; sans budget exprimé,
 * la demande est traitée comme sans limite de prix.
 */
public final class FallbackParser implements NlParser {

    public static final double UNLIMITED_BUDGET = JsonValidator.MAX_BUDGET_DT;

    private static final Pattern HOURS = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(?:h|heures?|hours?|hrs?)(?:\\s*(\\d{1,2}))?(?![a-z])");
    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*(?:min|minutes?|mn)(?![a-z])");
    private static final Pattern BUDGET = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(?:dt|dinars?|tnd)(?![a-z])");
    private static final Pattern BUDGET_WORD = Pattern.compile(
            "(?:budget|max(?:imum)?|jusqu'?a|pas plus de|under|up to)\\D{0,12}(\\d+(?:[.,]\\d+)?)");

    @Override
    public ParkingRequest parse(String text) throws NlParseException {
        if (text == null || text.isBlank()) {
            throw new NlParseException("demande vide");
        }
        String t = normalize(text);

        double duration = duration(t);
        double budget = budget(t);

        boolean pmr = has(t, "pmr", "handicap", "mobilite reduite", "fauteuil", "wheelchair", "disabled");
        boolean charging = has(t, "electrique", "electric", "recharge", "borne", "charging", "\\bev\\b",
                "tesla", "voiture electr");
        if (pmr && charging) {
            charging = false; // le besoin PMR prime : une seule contrainte forte à la fois
        }
        VehicleType type = pmr ? VehicleType.PMR : charging ? VehicleType.ELECTRIC : VehicleType.STANDARD;

        Priority priority = pmr ? Priority.PMR
                : has(t, "urgent", "urgence", "tres presse", "en retard", "emergency", "asap")
                ? Priority.URGENT : Priority.NORMAL;
        PreferredLocation loc = has(t, "pres de la sortie", "proche de la sortie", "pres sortie",
                "pres de l'sortie", "near the exit", "near exit", "close to the exit", "a cote de la sortie")
                ? PreferredLocation.NEAR_EXIT : PreferredLocation.ANY;

        return new ParkingRequest(null, null, duration, budget, type, charging, pmr, priority, loc, 0);
    }

    private static double duration(String t) throws NlParseException {
        if (has(t, "demi-heure", "demi heure", "half an hour", "half hour")) {
            return 0.5;
        }
        Matcher h = HOURS.matcher(t);
        if (h.find()) {
            double v = Double.parseDouble(h.group(1).replace(',', '.'));
            if (h.group(2) != null) { // « 1h30 »
                v += Integer.parseInt(h.group(2)) / 60.0;
            }
            return check(v);
        }
        Matcher m = MINUTES.matcher(t);
        if (m.find()) {
            return check(Integer.parseInt(m.group(1)) / 60.0);
        }
        throw new NlParseException("durée introuvable dans la demande");
    }

    private static double check(double v) throws NlParseException {
        if (v <= 0 || v > JsonValidator.MAX_DURATION_H) {
            throw new NlParseException("durée hors bornes : " + v);
        }
        return v;
    }

    private static double budget(String t) {
        Matcher m = BUDGET.matcher(t);
        if (m.find()) {
            return Double.parseDouble(m.group(1).replace(',', '.'));
        }
        m = BUDGET_WORD.matcher(t);
        if (m.find()) {
            return Double.parseDouble(m.group(1).replace(',', '.'));
        }
        return UNLIMITED_BUDGET;
    }

    private static boolean has(String t, String... keys) {
        for (String k : keys) {
            if (k.startsWith("\\b") ? Pattern.compile(k).matcher(t).find() : t.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return n.replace('’', '\'');
    }
}