package smartparking.services;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import smartparking.models.DecisionTrace;
import smartparking.models.ParkingConfig;
import smartparking.models.OfferEvaluation;
import smartparking.utils.JsonUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Les 5 outils que l'assistant LLM peut appeler, et seulement eux (liste blanche). Lecture seule :
 * aucun outil ne modifie l'état du parking. Les arguments sont validés avant toute exécution.
 * Chaque outil renvoie une observation JSON courte ; une erreur devient {"error": "..."}, jamais une exception.
 */
public final class ToolRegistry {

    public static final String GET_DECISION = "get_decision";
    public static final String GET_ZONE_STATUS = "get_zone_status";
    public static final String LIST_ZONES = "list_zones";
    public static final String ESTIMATE_PRICE = "estimate_price";
    public static final String GET_QUEUE_POSITION = "get_queue_position";

    public static final List<String> NAMES = List.of(GET_DECISION, GET_ZONE_STATUS, LIST_ZONES,
            ESTIMATE_PRICE, GET_QUEUE_POSITION);

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final int MAX_OBSERVATION_CHARS = 1500;

    private final ManagerView view;
    private final PricingPolicy pricing;

    public ToolRegistry(ManagerView view, PricingPolicy pricing) {
        this.view = view;
        this.pricing = pricing;
    }

    public static boolean isAllowed(String tool) {
        return tool != null && NAMES.contains(tool);
    }

    /** Retourne null si l'appel est autorisé, sinon la raison du refus. */
    public String validate(String tool, JsonObject args) {
        if (!isAllowed(tool)) {
            return "outil non autorisé : " + tool;
        }
        JsonObject a = args == null ? new JsonObject() : args;
        switch (tool) {
            case GET_DECISION, GET_QUEUE_POSITION:
                return validId(a, "requestId");
            case GET_ZONE_STATUS:
                return validZone(a);
            case ESTIMATE_PRICE:
                String zone = validZone(a);
                if (zone != null) {
                    return zone;
                }
                Double hours = number(a, "durationHours");
                if (hours == null || hours <= 0 || hours > JsonValidator.MAX_DURATION_H) {
                    return "durationHours doit être un nombre dans ]0, " + JsonValidator.MAX_DURATION_H + "]";
                }
                return null;
            default:
                return null;   // list_zones : aucun argument
        }
    }

    /** Exécute un outil déjà validé (revalide quand même : le Manager ne fait pas confiance à l'appelant). */
    public String execute(String tool, JsonObject args) {
        String problem = validate(tool, args);
        if (problem != null) {
            return error(problem);
        }
        String observation = switch (tool) {
            case GET_DECISION -> decision(args.get("requestId").getAsString());
            case GET_ZONE_STATUS -> zoneStatus(args.get("zoneId").getAsString());
            case LIST_ZONES -> listZones();
            case ESTIMATE_PRICE -> estimate(args.get("zoneId").getAsString(), args.get("durationHours").getAsDouble());
            default -> queuePosition(args.get("requestId").getAsString());
        };
        return observation.length() > MAX_OBSERVATION_CHARS
                ? observation.substring(0, MAX_OBSERVATION_CHARS) + "…" : observation;
    }

    private String decision(String requestId) {
        Optional<DecisionTrace> trace = view.decision(requestId);
        if (trace.isEmpty()) {
            return error("aucune décision enregistrée pour " + requestId);
        }
        DecisionTrace t = trace.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", t.requestId());
        out.put("selectedZone", t.selectedZoneId());
        out.put("summary", t.summary());
        out.put("scoreRule", "le score le plus PETIT est le MEILLEUR ; status VALID = offre valable, autre = éliminée");
        List<Map<String, Object>> evals = new ArrayList<>();
        for (OfferEvaluation e : t.evaluations()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("zone", e.zoneId());
            m.put("slot", e.slotId());
            m.put("price", e.totalPrice());
            m.put("score", e.score());
            m.put("status", e.status());
            m.put("reason", e.reason());
            evals.add(m);
        }
        out.put("evaluations", evals);
        return JsonUtil.toJson(out);
    }

    private String zoneStatus(String zoneId) {
        ParkingConfig.ZoneConfig zone = zone(zoneId);
        int occupied = Math.min(view.occupiedSlots(zoneId), zone.slotCount());
        double rate = (double) occupied / zone.slotCount();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("zoneId", zone.zoneId());
        out.put("type", zone.type().name());
        out.put("capacity", zone.slotCount());
        out.put("occupied", occupied);
        out.put("free", zone.slotCount() - occupied);
        out.put("occupancyPct", Math.round(rate * 100));
        out.put("down", view.isDown(zoneId));
        out.put("currentUnitPrice", pricing.unitPrice(zone.basePricePerHour(), rate));
        return JsonUtil.toJson(out);
    }

    private String listZones() {
        List<Map<String, Object>> zones = new ArrayList<>();
        for (ParkingConfig.ZoneConfig z : view.zones()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("zoneId", z.zoneId());
            m.put("type", z.type().name());
            m.put("capacity", z.slotCount());
            m.put("basePricePerHour", z.basePricePerHour());
            m.put("down", view.isDown(z.zoneId()));
            zones.add(m);
        }
        return JsonUtil.toJson(Map.of("zones", zones));
    }

    private String estimate(String zoneId, double hours) {
        ParkingConfig.ZoneConfig zone = zone(zoneId);
        double rate = (double) Math.min(view.occupiedSlots(zoneId), zone.slotCount()) / zone.slotCount();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("zoneId", zoneId);
        out.put("durationHours", hours);
        out.put("occupancyPct", Math.round(rate * 100));
        out.put("unitPrice", pricing.unitPrice(zone.basePricePerHour(), rate));
        out.put("totalPrice", pricing.totalPrice(zone.basePricePerHour(), rate, hours));
        return JsonUtil.toJson(out);
    }

    private String queuePosition(String requestId) {
        int position = view.queuePosition(requestId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        out.put("inQueue", position >= 0);
        if (position > 0) {
            out.put("position", position);
        }
        if (position == 0) {
            out.put("note", "nouvelle négociation en cours");
        }
        out.put("queueSize", view.queueSize());
        return JsonUtil.toJson(out);
    }

    /** Texte de secours quand le LLM est indisponible : la synthèse de la décision, sans rien inventer. */
    public static String explanationFromDecision(String observationJson) {
        try {
            JsonObject o = JsonParser.parseString(observationJson).getAsJsonObject();
            JsonElement error = o.get("error");
            if (error != null) {
                return "Je n'ai pas d'explication disponible : " + error.getAsString() + ".";
            }
            return o.get("summary").getAsString();
        } catch (RuntimeException e) {
            return "Je n'ai pas pu lire la décision demandée.";
        }
    }

    private ParkingConfig.ZoneConfig zone(String zoneId) {
        return view.zones().stream().filter(z -> z.zoneId().equals(zoneId)).findFirst().orElseThrow();
    }

    private String validZone(JsonObject a) {
        JsonElement e = a.get("zoneId");
        if (e == null || !e.isJsonPrimitive()) {
            return "zoneId manquant";
        }
        String id = e.getAsString();
        return view.zones().stream().anyMatch(z -> z.zoneId().equals(id)) ? null : "zone inconnue : " + id;
    }

    private static String validId(JsonObject a, String key) {
        JsonElement e = a.get(key);
        if (e == null || !e.isJsonPrimitive() || !ID.matcher(e.getAsString()).matches()) {
            return key + " manquant ou invalide";
        }
        return null;
    }

    private static Double number(JsonObject a, String key) {
        JsonElement e = a.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return null;
        }
        try {
            return e.getAsDouble();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String error(String message) {
        return JsonUtil.toJson(Map.of("error", message));
    }
}
