package smartparking.agents;

import com.google.gson.JsonSyntaxException;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import smartparking.models.Event;
import smartparking.services.MetricsCollector;
import smartparking.services.SimClock;
import smartparking.utils.Config;
import smartparking.utils.EventTypes;
import smartparking.utils.JsonUtil;
import smartparking.utils.Ontology;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Supervision : reçoit les événements du Manager et des zones, calcule les métriques (MetricsCollector)
 * et exporte les CSV quand l'exécution est terminée.
 *
 * Arguments de démarrage : {stratégie (String), scénario (String), graine (Long),
 * nombre de demandes attendues (Integer), arrêt automatique (Boolean)}.
 *
 * Surveillance des zones (étape 5B) : une zone qui n'a donné aucun signe de vie (HEARTBEAT) pendant
 * downAfterMissed x heartbeatMs est déclarée DOWN ; le Monitor prévient le Manager (INFORM ZONE_DOWN:<zone>,
 * ontologie smartpark-control). Au premier signe de vie suivant, la zone est déclarée UP (ZONE_UP:<zone>).
 *
 * L'exécution est terminée quand toutes les demandes ont reçu une décision et que le dernier
 * stationnement attribué est fini (temps simulé).
 */
public class MonitorAgent extends Agent {

    private static final long CHECK_PERIOD_MS = 500;
    private static final long WATCHDOG_PERIOD_MS = 100;
    private static final Path RESULTS_DIR = Path.of("results");

    private final MetricsCollector collector = new MetricsCollector();
    private String strategy = "?";
    private String scenario = "?";
    private long seed;
    private int expectedRequests;
    private boolean autoExit;
    private boolean finished;
    private long explainWaitSinceMs;
    private static final long EXPLAIN_WAIT_MS = 45_000;

    // surveillance des zones
    private long silenceLimitMs = Long.MAX_VALUE;
    private final Map<String, Long> lastSeenRealMs = new HashMap<>();
    private final Map<String, Double> faultStartSimMin = new HashMap<>();
    private final Map<String, Double> downSinceSimMin = new HashMap<>();
    private final List<Double> detectionDelays = new ArrayList<>();
    private final Map<String, Double> faultEndSimMin = new HashMap<>();
    private final List<Double> recoveryDelays = new ArrayList<>();
    private int downDetections;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args == null || args.length < 5) {
            System.err.println("[monitor] erreur : arguments attendus {stratégie, scénario, graine, demandes, arrêt auto}");
            doDelete();
            return;
        }
        strategy = args[0].toString();
        scenario = args[1].toString();
        seed = (Long) args[2];
        expectedRequests = (Integer) args[3];
        autoExit = (Boolean) args[4];

        try {
            var config = Config.load();
            silenceLimitMs = (long) config.downAfterMissed() * config.heartbeatMs();
        } catch (IOException | RuntimeException e) {
            System.err.println("[monitor] configuration illisible, pas de surveillance des zones : " + e.getMessage());
        }
        addBehaviour(new EventListener());
        addBehaviour(new TickerBehaviour(this, WATCHDOG_PERIOD_MS) {
            @Override
            protected void onTick() {
                checkZones();
            }
        });
        addBehaviour(new TickerBehaviour(this, CHECK_PERIOD_MS) {
            @Override
            protected void onTick() {
                checkFinished();
            }
        });
        System.out.println("[monitor] prêt : " + expectedRequests + " demandes attendues, stratégie " + strategy);
    }

    /** Lit les événements (INFORM) et les transmet au collecteur. */
    private class EventListener extends CyclicBehaviour {

        private final MessageTemplate template = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                MessageTemplate.MatchOntology(Ontology.NAME));

        @Override
        public void action() {
            ACLMessage message = myAgent.receive(template);
            if (message == null) {
                block();
                return;
            }
            try {
                Event event = JsonUtil.fromJson(message.getContent(), Event.class);
                collector.onEvent(event);
                onZoneEvent(event);
            } catch (JsonSyntaxException e) {
                System.err.println("[monitor] événement illisible ignoré");
            }
        }
    }

    /** Signes de vie et débuts/fins de panne (FAULT_ON/FAULT_OFF, utilisés seulement pour mesurer les délais). */
    private void onZoneEvent(Event event) {
        if (event == null || event.zoneId() == null) {
            return;
        }
        String zoneId = event.zoneId();
        if (EventTypes.FAULT_ON.equals(event.type())) {
            faultStartSimMin.put(zoneId, event.timestampSimMin());
        } else if (EventTypes.FAULT_OFF.equals(event.type())) {
            faultEndSimMin.put(zoneId, event.timestampSimMin());
        } else if (EventTypes.HEARTBEAT.equals(event.type())) {
            lastSeenRealMs.put(zoneId, System.currentTimeMillis());
            if (downSinceSimMin.containsKey(zoneId)) {
                declareUp(zoneId);
            }
        }
    }

    /** Déclare DOWN toute zone silencieuse depuis plus de downAfterMissed x heartbeatMs. */
    private void checkZones() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : lastSeenRealMs.entrySet()) {
            String zoneId = entry.getKey();
            if (!downSinceSimMin.containsKey(zoneId) && now - entry.getValue() > silenceLimitMs) {
                declareDown(zoneId, now - entry.getValue());
            }
        }
    }

    private void declareDown(String zoneId, long silentMs) {
        double now = SimClock.get().nowSimMin();
        downSinceSimMin.put(zoneId, now);
        downDetections++;
        Double started = faultStartSimMin.get(zoneId);
        if (started != null) {
            detectionDelays.add(now - started);
        }
        System.out.println(String.format(Locale.ROOT,
                "[monitor] %s DOWN (t = %.0f min) : aucun signe de vie depuis %d ms", zoneId, now, silentMs));
        tellManager(Ontology.ZONE_DOWN + ":" + zoneId);
    }

    private void declareUp(String zoneId) {
        double now = SimClock.get().nowSimMin();
        downSinceSimMin.remove(zoneId);
        Double ended = faultEndSimMin.get(zoneId);
        if (ended != null) {
            recoveryDelays.add(Math.max(0.0, now - ended));
        }
        System.out.println(String.format(Locale.ROOT, "[monitor] %s UP (t = %.0f min) : signe de vie reçu", zoneId, now));
        tellManager(Ontology.ZONE_UP + ":" + zoneId);
    }

    private void tellManager(String content) {
        ACLMessage message = new ACLMessage(ACLMessage.INFORM);
        message.addReceiver(new AID(ParkingManagerAgent.MANAGER_NAME, AID.ISLOCALNAME));
        message.setOntology(Ontology.CONTROL);
        message.setContent(content);
        send(message);
    }

    private static double mean(List<Double> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    private void checkFinished() {
        if (finished || collector.decidedCount() < expectedRequests) {
            return;
        }
        if (SimClock.get().nowSimMin() < collector.latestEndSimMin()) {
            return;   // des véhicules sont encore garés
        }
        if (Boolean.getBoolean("smartpark.explain") && collector.explainDoneCount() < collector.allocatedCount()) {
            long now = System.currentTimeMillis();
            if (explainWaitSinceMs == 0) {
                explainWaitSinceMs = now;
            }
            if (now - explainWaitSinceMs < EXPLAIN_WAIT_MS) {
                return;   // des explications du LLM sont encore en cours
            }
            System.out.println("[monitor] " + (collector.allocatedCount() - collector.explainDoneCount())
                    + " explication(s) non terminée(s) après " + EXPLAIN_WAIT_MS / 1000 + " s, arrêt quand même");
        }
        finished = true;
        printAndExport();
        if (autoExit) {
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                System.exit(0);
            }).start();
        }
    }

    private void printAndExport() {
        MetricsCollector.Summary s = collector.summary(scenario, strategy, seed);
        System.out.println("[monitor] ===== RÉSULTATS " + scenario + " / " + strategy + " / graine " + seed + " =====");
        System.out.println(String.format(Locale.ROOT,
                "[monitor] demandes %d : %d attribuées, %d refusées, %d échecs, satisfaction %.1f %%",
                s.requests(), s.allocated(), s.refused(), s.failed(), s.satisfactionRate() * 100));
        System.out.println(String.format(Locale.ROOT,
                "[monitor] temps moyen d'attribution %.1f ms, attente simulée moyenne %.2f min, "
                        + "occupation moyenne %.1f %%", s.avgAssignmentMs(), s.avgWaitSimMin(),
                s.avgOccupancy() * 100));
        System.out.println(String.format(Locale.ROOT,
                "[monitor] revenu %.2f DT, conflits %d, places spécialisées sans besoin %d, "
                        + "doubles attributions %d",
                s.revenue(), s.conflicts(), s.unneededSpecialized(), s.doubleAllocations()));
        System.out.println(String.format(Locale.ROOT,
                "[monitor] mises en file %d, distance moyenne à la sortie %.1f m "
                        + "(véhicules NEAR_EXIT : %.1f m)",
                s.queued(), s.avgDistanceToExit(), s.avgDistanceNearExit()));
        MetricsCollector.LlmStats llm = collector.llmStats();
        if (!llm.isEmpty()) {
            System.out.println(String.format(Locale.ROOT,
                    "[monitor] LLM : %d phrases analysées (%d par les règles, %d mal comprises, latence moyenne %.0f ms), "
                            + "%d appels ReAct (%.0f ms), %d replis, %d appels d'outils, %d outils refusés",
                    llm.parseCalls(), llm.parsedByRules(), llm.mismatchedRequests(), llm.avgParseLatencyMs(),
                    llm.reactCalls(), llm.avgReactLatencyMs(), llm.fallbacks(), llm.toolCalls(), llm.toolRefused()));
        }
        if (downDetections > 0) {
            System.out.println(String.format(Locale.ROOT,
                    "[monitor] pannes détectées %d, délai moyen de détection %.1f min simulées, "
                            + "délai moyen de réintégration %.1f min simulées",
                    downDetections, mean(detectionDelays), mean(recoveryDelays)));
        }
        try {
            Path detail = collector.export(RESULTS_DIR, scenario, strategy, seed);
            System.out.println("[monitor] fichiers écrits : " + detail + " et " + RESULTS_DIR.resolve("summary.csv"));
        } catch (IOException e) {
            System.err.println("[monitor] export CSV impossible : " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        System.out.println("[monitor] arrêté");
    }
}
