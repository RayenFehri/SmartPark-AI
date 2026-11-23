package smartparking.agents;

import com.google.gson.JsonSyntaxException;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import smartparking.behaviours.EventReporter;
import smartparking.behaviours.ManagerCnpInitiator;
import smartparking.models.Allocation;
import smartparking.models.DecisionTrace;
import smartparking.models.ParkingConfig;
import smartparking.models.ParkingRequest;
import smartparking.models.RefusalReason;
import smartparking.models.Strategy;
import smartparking.services.AllocationScorer;
import smartparking.utils.Config;
import smartparking.utils.EventTypes;
import smartparking.utils.JsonUtil;
import smartparking.utils.Ontology;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinateur du parking. Reçoit les demandes des véhicules (FIPA-Request), lance un Contract Net
 * par demande (ManagerCnpInitiator), garde la trace de chaque décision et tient le bilan.
 *
 * Étape 3 : stratégie CNP_OPTIMIZED uniquement, sans file d'attente ni détection de panne
 * (ces fonctions arrivent aux étapes 4 et 5).
 */
public class ParkingManagerAgent extends Agent {

    private final Map<String, DecisionTrace> traces = new LinkedHashMap<>();
    /** Place -> fin de stationnement (minutes simulées) : sert à détecter toute double attribution. */
    private final Map<String, Double> slotOccupiedUntil = new HashMap<>();
    private final Set<String> downZones = ConcurrentHashMap.newKeySet();

    private int allocatedCount;
    private int refusedCount;
    private int failedCount;
    private int doubleAllocations;
    private double revenue;

    private ManagerCnpInitiator.Deps deps;

    @Override
    protected void setup() {
        ParkingConfig config;
        try {
            config = Config.load();
        } catch (IOException | RuntimeException e) {
            System.err.println("[Manager] configuration invalide : " + e.getMessage());
            doDelete();
            return;
        }

        // la stratégie du fichier de configuration peut être remplacée par l'option --strategy du Launcher
        Strategy strategy = Strategy.valueOf(
                System.getProperty("smartpark.strategy", config.strategy().name()));
        if (strategy == Strategy.CNP_LLM) {
            System.err.println("[Manager] la stratégie CNP_LLM n'existe pas encore (étape 7) : CNP_OPTIMIZED utilisée");
            strategy = Strategy.CNP_OPTIMIZED;
        }

        deps = new ManagerCnpInitiator.Deps(strategy, new AllocationScorer(config), this::findZones,
                () -> downZones, new ManagerOutcomes(), config.cnpTimeoutMs());

        addBehaviour(new RequestListener());
        System.out.println("[Manager] prêt, stratégie " + strategy);
    }

    /** Écoute les REQUEST des véhicules : répond AGREE, puis laisse le CNP répondre INFORM/REFUSE/FAILURE. */
    private class RequestListener extends CyclicBehaviour {

        private final MessageTemplate template = MessageTemplate.and(
                MessageTemplate.MatchProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST),
                MessageTemplate.and(
                        MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                        MessageTemplate.MatchOntology(Ontology.NAME)));

        @Override
        public void action() {
            ACLMessage message = myAgent.receive(template);
            if (message == null) {
                block();
                return;
            }

            ParkingRequest request = readRequest(message);
            if (request == null || request.requestId() == null) {
                ACLMessage reply = message.createReply();
                reply.setPerformative(ACLMessage.NOT_UNDERSTOOD);
                reply.setContent("demande illisible");
                myAgent.send(reply);
                System.out.println("[Manager] demande illisible -> NOT_UNDERSTOOD");
                return;
            }

            EventReporter.report(myAgent, EventTypes.REQUEST_SENT, request.requestId(), null,
                    Map.of("vehicleId", request.vehicleId(), "arrivalSimMin", request.arrivalSimMin()));

            ACLMessage agree = message.createReply();
            agree.setPerformative(ACLMessage.AGREE);
            agree.setContent("demande reçue");
            myAgent.send(agree);
            System.out.println("[Manager] demande " + request.requestId() + " de " + request.vehicleId()
                    + " : " + request.duration() + " h, budget " + request.maxBudget() + " DT, "
                    + request.vehicleType() + " -> AGREE, lancement du CNP");

            ManagerCnpInitiator.launch(myAgent, deps, request, message, 1);
        }
    }

    private static ParkingRequest readRequest(ACLMessage message) {
        try {
            return JsonUtil.fromJson(message.getContent(), ParkingRequest.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    /** Zones actives trouvées au DF (les zones DOWN sont exclues). */
    private List<AID> findZones() {
        List<AID> zones = new ArrayList<>();
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription service = new ServiceDescription();
            service.setType(Ontology.ZONE_SERVICE_TYPE);
            template.addServices(service);
            for (DFAgentDescription found : DFService.search(this, template)) {
                if (!downZones.contains(found.getName().getLocalName())) {
                    zones.add(found.getName());
                }
            }
        } catch (FIPAException e) {
            System.err.println("[Manager] erreur DF : " + e.getMessage());
        }
        return zones;
    }

    private void printTally() {
        System.out.println(String.format(java.util.Locale.ROOT,
                "[Manager] BILAN : %d attribuée(s) (revenu %.2f DT), %d refusée(s), %d échec(s), "
                        + "%d double(s) attribution(s)",
                allocatedCount, revenue, refusedCount, failedCount, doubleAllocations));
    }

    /** Réception des résultats des négociations (toujours dans le thread de l'agent). */
    private class ManagerOutcomes implements ManagerCnpInitiator.Outcomes {

        @Override
        public void trace(DecisionTrace trace) {
            traces.put(trace.requestId(), trace);
        }

        @Override
        public void conflict(String requestId, String zoneId) {
            EventReporter.report(ParkingManagerAgent.this, EventTypes.CONFLICT, requestId, zoneId, null);
        }

        @Override
        public void allocated(Allocation a, boolean wasteful) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("zoneId", a.zoneId());
            details.put("slotId", a.slotId());
            details.put("price", a.finalPrice());
            details.put("score", a.score());
            details.put("startSimMin", a.startSimMin());
            details.put("endSimMin", a.endSimMin());
            details.put("wasteful", wasteful);
            EventReporter.report(ParkingManagerAgent.this, EventTypes.ALLOCATED, a.requestId(), a.zoneId(), details);

            Double busyUntil = slotOccupiedUntil.get(a.slotId());
            if (busyUntil != null && busyUntil > a.startSimMin() + 1e-6) {
                doubleAllocations++;
                System.out.println("[Manager] !!! DOUBLE ATTRIBUTION détectée sur " + a.slotId());
            }
            slotOccupiedUntil.put(a.slotId(), a.endSimMin());
            allocatedCount++;
            revenue += a.finalPrice();
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[Manager] %s -> %s, place %s, %.2f DT, score %.3f", a.requestId(), a.zoneId(),
                    a.slotId(), a.finalPrice(), a.score()));
            printTally();
        }

        @Override
        public void refused(String requestId, RefusalReason reason) {
            refusedCount++;
            EventReporter.report(ParkingManagerAgent.this, EventTypes.REFUSED, requestId, null,
                    Map.of("reason", reason.name()));
            System.out.println("[Manager] " + requestId + " -> REFUSÉE (" + reason + ")");
            printTally();
        }

        @Override
        public void failed(String requestId, String why) {
            failedCount++;
            EventReporter.report(ParkingManagerAgent.this, EventTypes.FAILED, requestId, null,
                    Map.of("reason", why));
            System.out.println("[Manager] " + requestId + " -> ÉCHEC (" + why + ")");
            printTally();
        }
    }
}