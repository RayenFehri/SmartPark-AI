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
 *  - ALLOCATED    : {zoneId, slotId, price, score, startSimMin, endSimMin, wasteful}
 *  - REFUSED      : {reason}
 *  - FAILED       : {reason}
 *  - CONFLICT     : un ACCEPT_PROPOSAL sur une place qui n'est plus réservée à ce véhicule (§10)
 *  - HEARTBEAT    : {used, usable} : places utilisées et utilisables de la zone (champ zoneId)
 */
public class MetricsCollector {

    /** Résultat agrégé d'une exécution (une ligne de summary.csv). */
    public record Summary(String scenario, String strategy, long seed,
                          int requests, int allocated, int refused, int failed,
                          double satisfactionRate, double avgAssignmentMs, double avgWaitSimMin,
                          double avgOccupancy, int conflicts, double revenue,
                          int unneededSpecialized, int doubleAllocations) { }

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
    }

    private final Map<String, Row> rows = new LinkedHashMap<>();
    /** Dernier état connu de chaque zone : {used, usable}. */
    private final Map<String, int[]> zoneLoad = new HashMap<>();
    private final List<Double> occupancySamples = new ArrayList<>();
    private final Map<String, Double> slotOccupiedUntil = new HashMap<>();

    private int conflicts;
    private int unneededSpecialized;
    private int doubleAllocations;
    private double revenue;
    private double latestEndSimMin;

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
            case EventTypes.CONFLICT -> conflicts++;
            case EventTypes.HEARTBEAT -> onHeartbeat(event);
            default -> { }
        }
    }

    private void onAllocated(Event event) {
        Map<?, ?> details = parse(event.details());
        Row row = rows.computeIfAbsent(event.requestId(), id -> new Row());
        row.outcome = "ALLOCATED";
        row.zoneId = text(details, "zoneId");
        row.slotId = text(details, "slotId");
        row.price = number(details, "price");
        row.score = number(details, "score");
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
        for (Row row : rows.values()) {
            switch (row.outcome) {
                case "ALLOCATED" -> {
                    allocated++;
                    assignmentSum += row.assignmentMs;
                    waitSum += row.waitSimMin;
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
                allocated == 0 ? 0.0 : assignmentSum / allocated,
                allocated == 0 ? 0.0 : waitSum / allocated,
                occupancy, conflicts, revenue, unneededSpecialized, doubleAllocations);
    }

    public static String summaryHeader() {
        return "scenario,strategy,seed,requests,allocated,refused,failed,satisfaction_rate,"
                + "avg_assignment_ms,avg_wait_sim_min,avg_occupancy,conflicts,revenue,"
                + "unneeded_specialized,double_allocations";
    }

    public static String summaryLine(Summary s) {
        return String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%.4f,%.2f,%.2f,%.4f,%d,%.2f,%d,%d",
                s.scenario(), s.strategy(), s.seed(), s.requests(), s.allocated(), s.refused(), s.failed(),
                s.satisfactionRate(), s.avgAssignmentMs(), s.avgWaitSimMin(), s.avgOccupancy(), s.conflicts(),
                s.revenue(), s.unneededSpecialized(), s.doubleAllocations());
    }

    /** Détail par demande, au format CSV (une ligne par demande, dans l'ordre d'arrivée au Manager). */
    public synchronized List<String> detailLines() {
        List<String> lines = new ArrayList<>();
        lines.add("request_id,outcome,zone_id,slot_id,price,score,assignment_ms,wait_sim_min");
        for (Map.Entry<String, Row> entry : rows.entrySet()) {
            Row r = entry.getValue();
            lines.add(String.format(Locale.ROOT, "%s,%s,%s,%s,%.2f,%.4f,%d,%.2f", entry.getKey(), r.outcome,
                    r.zoneId, r.slotId, r.price, r.score, r.assignmentMs, r.waitSimMin));
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