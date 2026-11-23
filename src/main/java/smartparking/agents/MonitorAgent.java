package smartparking.agents;

import com.google.gson.JsonSyntaxException;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import smartparking.models.Event;
import smartparking.services.MetricsCollector;
import smartparking.services.SimClock;
import smartparking.utils.JsonUtil;
import smartparking.utils.Ontology;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Supervision : reçoit les événements du Manager et des zones, calcule les métriques (MetricsCollector)
 * et exporte les CSV quand l'exécution est terminée.
 *
 * Arguments de démarrage : {stratégie (String), scénario (String), graine (Long),
 * nombre de demandes attendues (Integer), arrêt automatique (Boolean)}.
 *
 * L'exécution est terminée quand toutes les demandes ont reçu une décision et que le dernier
 * stationnement attribué est fini (temps simulé).
 */
public class MonitorAgent extends Agent {

    private static final long CHECK_PERIOD_MS = 500;
    private static final Path RESULTS_DIR = Path.of("results");

    private final MetricsCollector collector = new MetricsCollector();
    private String strategy = "?";
    private String scenario = "?";
    private long seed;
    private int expectedRequests;
    private boolean autoExit;
    private boolean finished;

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

        addBehaviour(new EventListener());
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
            } catch (JsonSyntaxException e) {
                System.err.println("[monitor] événement illisible ignoré");
            }
        }
    }

    private void checkFinished() {
        if (finished || collector.decidedCount() < expectedRequests) {
            return;
        }
        if (SimClock.get().nowSimMin() < collector.latestEndSimMin()) {
            return;   // des véhicules sont encore garés
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