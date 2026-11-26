package smartparking.services;

import com.google.gson.JsonSyntaxException;
import smartparking.models.Event;
import smartparking.utils.EventTypes;
import smartparking.utils.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calcule les métriques d'une exécution à partir du flux d'événements reçus par le MonitorAgent
 * (cahier des charges, §12), et les exporte en CSV. Java pur, sans dépendance à JADE.
 *
 * Conventions des événements (champ details = JSON) :
 *  - REQUEST_SENT : reçu par le Manager (timestamp réel et simulé de réception)
 *  - ALLOCATED    : {zoneId, slotId, price, score, startSimMin, endSimMin, wasteful,
 *                    distanceToExit, nearExit}
 *  - REFUSED      : {reason}
 *  - FAILED       : {reason}
 *  - CONFLICT     : un ACCEPT_PROPOSAL sur une place qui n'est plus réservée à ce véhicule (§10)
 *  - QUEUED       : la demande est mise en file d'attente (saturation)
 *  - HEARTBEAT    : {used, usable} : places utilisées et utilisables de la zone (champ zoneId)
 */
public class MetricsCollector {

    /** Résultat agrégé d'une exécution (une ligne de summary.csv). */
    public record Summary(String scenario, String strategy, long seed,
                          int requests, int allocated, int refused, int failed,
                          double satisfactionRate, double avgAssignmentMs, double avgWaitSimMin,
                          double avgOccupancy, int conflicts, double revenue,
                          int unneededSpecialized, int doubleAllocations,
                          int queued, double avgDistanceToExit, double avgDistanceNearExit) { }

    /**
     * Mesures de la couche LLM (stratégie C), hors summary.csv : appels, replis sur les règles, phrases mal comprises,
     * appels d'outils et latences moyennes.
     */
    public record LlmStats(int parseCalls, int parsedByRules, int mismatchedRequests, int reactCalls,
                           int fallbacks, int toolCalls, int toolRefused,
                           double avgParseLatencyMs, double avgReactLatencyMs) {
        public boolean isEmpty() {
            return parseCalls == 0 && reactCalls == 0 && toolCalls == 0 && toolRefused == 0;
        }
    }

    private static final class Row {
        String outcome = "PENDING";
        String zoneId = "";
        String slotId = "";
        double price;
        double score;
        long receivedRealMs;
        double receivedSimMin;
        long assignmentMs = -1;
        double waitSimMin = -1;
        double distanceToExit;
        boolean nearExitRequested;
    }

    private final Map<String, Row> rows = new LinkedHashMap<>();
    /** Dernier état connu de chaque zone : {used, usable}. */
    private final Map<String, int[]> zoneLoad = new HashMap<>();
    private final List<Double> occupancySamples = new ArrayList<>();
    private final Map<String, Double> slotOccupiedUntil = new HashMap<>();
    private final java.util.Set<String> queuedRequests = new java.util.HashSet<>();

    private int parseCalls;
    private int parsedByRules;
    private int mismatchedRequests;
    private int reactCalls;
    private int llmFallbacks;
    private int toolCalls;
    private int toolRefused;
    private double parseLatencySum;
    private double reactLatencySum;

    private int conflicts;
    private int unneededSpecialized;
    private int doubleAllocations;
    private double revenue;
    private double latestEndSimMin;
    private int explainDone;

    /** Enregistre un événement. Les événements inconnus ou mal formés sont ignorés. */
    public synchronized void onEvent(Event event) {
        if (event == null || event.type() == null) {
            return;
        }
        switch (event.type()) {
            case EventTypes.REQUEST_SENT -> {
                Row row = rows.computeIfAbsent(event.requestId(), id -> new Row());
                row.receivedRealMs = event.timestampRealMs();
                row.receivedSimMin = event.timestampSimMin();
            }
            case EventTypes.ALLOCATED -> onAllocated(event);
            case EventTypes.REFUSED -> rows.computeIfAbsent(event.requestId(), id -> new Row()).outcome = "REFUSED";
            case EventTypes.FAILED -> rows.computeIfAbsent(event.requestId(), id -> new Row()).outcome = "FAILED";
            case EventTypes.QUEUED -> queuedRequests.add(event.requestId());
            case EventTypes.CONFLICT -> conflicts++;
            case EventTypes.HEARTBEAT -> onHeartbeat(event);
            case EventTypes.LLM_CALL -> onLlmCall(event);
            case EventTypes.LLM_FALLBACK -> llmFallbacks++;
            case EventTypes.TOOL_CALL -> toolCalls++;
            case EventTypes.EXPLAIN_DONE -> explainDone++;
            case EventTypes.TOOL_REFUSED -> toolRefused++;
            default -> { }
        }
    }

    private void onLlmCall(Event event) {
        Map<?, ?> details = parse(event.details());
        double latency = number(details, "latencyMs");
        if ("react".equals(details.get("purpose"))) {
            reactCalls++;
            reactLatencySum += latency;
            return;
        }
        parseCalls++;
        parseLatencySum += latency;
        if ("FALLBACK".equals(details.get("source"))) {
            parsedByRules++;
        }
        if (details.get("mismatches") instanceof List<?> list && !list.isEmpty()) {
            mismatchedRequests++;
        }
    }

    public synchronized LlmStats llmStats() {
        return new LlmStats(parseCalls, parsedByRules, mismatchedRequests, reactCalls, llmFallbacks, toolCalls,
                toolRefused, parseCalls == 0 ? 0.0 : parseLatencySum / parseCalls,
                reactCalls == 0 ? 0.0 : reactLatencySum / reactCalls);
    }

    private void onAllocated(Event event) {
        Map<?, ?> details = parse(event.details());
        Row row = rows.computeIfAbsent(event.requestId(), id -> new Row());
        row.outcome = "ALLOCATED";
        row.zoneId = text(details, "zoneId");
        row.slotId = text(details, "slotId");
        row.price = number(details, "price");
        row.score = number(details, "score");
        row.distanceToExit = number(details, "distanceToExit");
        row.nearExitRequested = Boolean.TRUE.equals(details.get("nearExit"));
        row.assignmentMs = Math.max(0, event.timestampRealMs() - row.receivedRealMs);
        row.waitSimMin = Math.max(0.0, event.timestampSimMin() - row.receivedSimMin);
        revenue += row.price;

        if (Boolean.TRUE.equals(details.get("wasteful"))) {
            unneededSpecialized++;
        }
        // une place ne peut pas être attribuée tant que la précédente attribution n'est pas terminée
        Double busyUntil = slotOccupiedUntil.get(row.slotId);
        double start = number(details, "startSimMin");
        if (busyUntil != null && busyUntil > start + 1e-6) {
            doubleAllocations++;
        }
        slotOccupiedUntil.put(row.slotId, number(details, "endSimMin"));
        latestEndSimMin = Math.max(latestEndSimMin, number(details, "endSimMin"));
    }

    private void onHeartbeat(Event event) {
        Map<?, ?> details = parse(event.details());
        if (event.zoneId() == null || !details.containsKey("usable")) {
            return;
        }
        zoneLoad.put(event.zoneId(), new int[] {(int) number(details, "used"), (int) number(details, "usable")});
        int used = 0;
        int usable = 0;
        for (int[] load : zoneLoad.values()) {
            used += load[0];
            usable += load[1];
        }
        if (usable > 0) {
            occupancySamples.add((double) used / usable);
        }
    }

    /** Nombre de demandes ayant reçu une décision finale (attribuée, refusée ou en échec). */
    public synchronized int explainDoneCount() {
        return explainDone;
    }

    public synchronized int allocatedCount() {
        return (int) rows.values().stream().filter(r -> "ALLOCATED".equals(r.outcome)).count();
    }

    public synchronized int decidedCount() {
        int decided = 0;
        for (Row row : rows.values()) {
            if (!"PENDING".equals(row.outcome)) {
                decided++;
            }
        }
        return decided;
    }

    /** Instant simulé où se termine le dernier stationnement attribué (0 si aucune attribution). */
    public synchronized double latestEndSimMin() {
        return latestEndSimMin;
    }

    public synchronized Summary summary(String scenario, String strategy, long seed) {
        int allocated = 0;
        int refused = 0;
        int failed = 0;
        double assignmentSum = 0;
        double waitSum = 0;
        double distanceSum = 0;
        double nearExitDistanceSum = 0;
        int nearExitCount = 0;
        int direct = 0;
        for (Map.Entry<String, Row> entry : rows.entrySet()) {
            String id = entry.getKey();
            Row row = entry.getValue();
            switch (row.outcome) {
                case "ALLOCATED" -> {
                    allocated++;
                    if (!queuedRequests.contains(id)) {
                        assignmentSum += row.assignmentMs;
                        direct++;
                    }
                    waitSum += row.waitSimMin;
                    distanceSum += row.distanceToExit;
                    if (row.nearExitRequested) {
                        nearExitDistanceSum += row.distanceToExit;
                        nearExitCount++;
                    }
                }
                case "REFUSED" -> refused++;
                case "FAILED" -> failed++;
                default -> { }
            }
        }
        int requests = rows.size();
        double occupancy = occupancySamples.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return new Summary(scenario, strategy, seed, requests, allocated, refused, failed,
                requests == 0 ? 0.0 : (double) allocated / requests,
                direct == 0 ? 0.0 : assignmentSum / direct,
                allocated == 0 ? 0.0 : waitSum / allocated,
                occupancy, conflicts, revenue, unneededSpecialized, doubleAllocations,
                queuedRequests.size(),
                allocated == 0 ? 0.0 : distanceSum / allocated,
                nearExitCount == 0 ? 0.0 : nearExitDistanceSum / nearExitCount);
    }

    public static String summaryHeader() {
        return "scenario,strategy,seed,requests,allocated,refused,failed,satisfaction_rate,"
                + "avg_assignment_ms,avg_wait_sim_min,avg_occupancy,conflicts,revenue,"
                + "unneeded_specialized,double_allocations,queued,avg_distance_exit,avg_distance_near_exit";
    }

    public static String summaryLine(Summary s) {
        return String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%.4f,%.2f,%.2f,%.4f,%d,%.2f,%d,%d,%d,%.2f,%.2f",
                s.scenario(), s.strategy(), s.seed(), s.requests(), s.allocated(), s.refused(), s.failed(),
                s.satisfactionRate(), s.avgAssignmentMs(), s.avgWaitSimMin(), s.avgOccupancy(), s.conflicts(),
                s.revenue(), s.unneededSpecialized(), s.doubleAllocations(), s.queued(),
                s.avgDistanceToExit(), s.avgDistanceNearExit());
    }

    /** Détail par demande, au format CSV (une ligne par demande, dans l'ordre d'arrivée au Manager). */
    public synchronized List<String> detailLines() {
        List<String> lines = new ArrayList<>();
        lines.add("request_id,outcome,zone_id,slot_id,price,score,assignment_ms,wait_sim_min,distance_exit");
        for (Map.Entry<String, Row> entry : rows.entrySet()) {
            Row r = entry.getValue();
            lines.add(String.format(Locale.ROOT, "%s,%s,%s,%s,%.2f,%.4f,%d,%.2f,%.1f", entry.getKey(), r.outcome,
                    r.zoneId, r.slotId, r.price, r.score, r.assignmentMs, r.waitSimMin, r.distanceToExit));
        }
        return lines;
    }

    /**
     * Écrit detail_{scénario}_{stratégie}_seed{n}.csv et ajoute une ligne à summary.csv
     * (créé avec son en-tête s'il n'existe pas). Retourne le fichier de détail.
     */
    public Path export(Path directory, String scenario, String strategy, long seed) throws IOException {
        Files.createDirectories(directory);
        Path detail = directory.resolve(String.format("detail_%s_%s_seed%d.csv", scenario, strategy, seed));
        Files.write(detail, detailLines(), StandardCharsets.UTF_8);

        Path summaryFile = directory.resolve("summary.csv");
        List<String> toAppend = new ArrayList<>();
        if (!Files.exists(summaryFile)) {
            toAppend.add(summaryHeader());
        }
        toAppend.add(summaryLine(summary(scenario, strategy, seed)));
        Files.write(summaryFile, toAppend, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return detail;
    }

    private static Map<?, ?> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<?, ?> map = JsonUtil.fromJson(json, Map.class);
            return map == null ? Map.of() : map;
        } catch (JsonSyntaxException e) {
            return Map.of();
        }
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }

    private static double number(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Number n ? n.doubleValue() : 0.0;
    }
}
