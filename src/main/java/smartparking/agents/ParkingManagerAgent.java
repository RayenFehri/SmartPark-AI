package smartparking.agents;

import com.google.gson.JsonSyntaxException;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
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
import smartparking.models.Alternative;
import smartparking.models.DecisionTrace;
import smartparking.models.ParkingConfig;
import smartparking.models.ParkingRequest;
import smartparking.models.Priority;
import smartparking.models.RefusalReason;
import smartparking.models.Strategy;
import smartparking.services.AllocationScorer;
import smartparking.services.AlternativeAdvisor;
import smartparking.services.SimClock;
import smartparking.services.WaitQueue;
import smartparking.utils.Config;
import smartparking.utils.EventTypes;
import smartparking.utils.JsonUtil;
import smartparking.utils.Ontology;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinateur du parking. Reçoit les demandes des véhicules (FIPA-Request), lance un Contract Net
 * par demande (ManagerCnpInitiator), garde la trace de chaque décision et tient le bilan.
 *
 * Saturation (EF10) : quand toutes les zones compatibles sont pleines, la demande attend dans une file
 * (FIFO, PMR et URGENT prioritaires) pendant au plus queueMaxWaitSimMin minutes simulées. La file est relancée
 * quand une zone signale une place libre (et au moins une fois par seconde). Au-delà de l'attente maximale,
 * le véhicule est refusé avec des alternatives.
 *
 * Pas encore traité (étape 5B) : détection de panne des zones.
 */
public class ParkingManagerAgent extends Agent {

    public static final String MANAGER_NAME = "manager";

    private static final long QUEUE_TICK_MS = 250;
    private static final long QUEUE_RETRY_PERIOD_MS = 5000;

    /** Demande en attente : le message REQUEST d'origine sert à répondre au véhicule le moment venu. */
    private record QueuedRequest(ParkingRequest request, ACLMessage vehicleRequest, int round) { }

    private final Map<String, DecisionTrace> traces = new LinkedHashMap<>();
    /** Place -> fin de stationnement (minutes simulées) : sert à détecter toute double attribution. */
    private final Map<String, Double> slotOccupiedUntil = new HashMap<>();
    /** Zone -> fins de stationnement connues : sert à estimer l'attente dans les alternatives. */
    private final Map<String, List<Double>> endsByZone = new HashMap<>();
    private final Set<String> downZones = ConcurrentHashMap.newKeySet();

    private final WaitQueue<QueuedRequest> queue = new WaitQueue<>();
    /** Éléments sortis de la file pour une nouvelle négociation (leur rang est conservé). */
    private final Map<String, WaitQueue.Entry<QueuedRequest>> inFlight = new HashMap<>();
    private boolean retryRequested;
    private long lastRetryMs;
    private int queueMaxWaitSimMin;

    private int allocatedCount;
    private int refusedCount;
    private int failedCount;
    private int queuedCount;
    private int doubleAllocations;
    private double revenue;

    private ManagerCnpInitiator.Deps deps;
    private AlternativeAdvisor advisor;

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

        queueMaxWaitSimMin = config.queueMaxWaitSimMin();
        advisor = new AlternativeAdvisor(config);
        deps = new ManagerCnpInitiator.Deps(strategy, new AllocationScorer(config), this::findZones,
                () -> downZones, new ManagerOutcomes(), config.cnpTimeoutMs(), queueMaxWaitSimMin > 0);

        addBehaviour(new RequestListener());
        addBehaviour(new SlotReleasedListener());
        addBehaviour(new WaitQueueTicker(this));
        System.out.println("[Manager] prêt, stratégie " + strategy + ", attente maximale "
                + queueMaxWaitSimMin + " min simulées");
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

    /** Une zone signale une place libérée : la file d'attente sera relancée au prochain tick. */
    private class SlotReleasedListener extends CyclicBehaviour {

        private final MessageTemplate template = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                MessageTemplate.and(
                        MessageTemplate.MatchOntology(Ontology.NAME),
                        MessageTemplate.MatchContent(Ontology.SLOT_RELEASED)));

        @Override
        public void action() {
            ACLMessage message = myAgent.receive(template);
            if (message == null) {
                block();
                return;
            }
            retryRequested = true;
        }
    }

    /** File d'attente : refuse les demandes qui attendent trop, relance les autres quand une place se libère. */
    private class WaitQueueTicker extends TickerBehaviour {

        WaitQueueTicker(Agent agent) {
            super(agent, QUEUE_TICK_MS);
        }

        @Override
        protected void onTick() {
            double now = SimClock.get().nowSimMin();
            for (WaitQueue.Entry<QueuedRequest> expired : queue.removeExpired(now, queueMaxWaitSimMin)) {
                QueuedRequest q = expired.payload();
                System.out.println(String.format(Locale.ROOT,
                        "[Manager] %s : attente maximale dépassée (%.0f min simulées) -> REFUS",
                        q.request().requestId(), now - expired.enqueuedSimMin()));
                ManagerCnpInitiator.refuse(myAgent, deps, q.request(), q.vehicleRequest(), RefusalReason.ZONE_FULL);
            }
            if (queue.isEmpty()) {
                return;
            }
            long nowMs = System.currentTimeMillis();
            if (!retryRequested && nowMs - lastRetryMs < QUEUE_RETRY_PERIOD_MS) {
                return;
            }
            retryRequested = false;
            lastRetryMs = nowMs;

            // les CFP partent dans l'ordre de service : les zones les traitent dans cet ordre (PMR d'abord)
            for (WaitQueue.Entry<QueuedRequest> entry : queue.drainAll()) {
                QueuedRequest q = entry.payload();
                inFlight.put(entry.id(), entry);
                ManagerCnpInitiator.launch(myAgent, deps, q.request(), q.vehicleRequest(), 1, q.round() + 1);
            }
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
        System.out.println(String.format(Locale.ROOT,
                "[Manager] BILAN : %d attribuée(s) (revenu %.2f DT), %d refusée(s), %d échec(s), "
                        + "%d en file, %d double(s) attribution(s)",
                allocatedCount, revenue, refusedCount, failedCount, queue.size() + inFlight.size(),
                doubleAllocations));
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
        public List<Alternative> alternatives(ParkingRequest request, RefusalReason reason) {
            return advisor.advise(request, reason, endsByZone, SimClock.get().nowSimMin());
        }

        @Override
        public void queued(ParkingRequest request, ACLMessage vehicleRequest, int round) {
            QueuedRequest queued = new QueuedRequest(request, vehicleRequest, round);
            WaitQueue.Entry<QueuedRequest> previous = inFlight.remove(request.requestId());
            if (previous != null) {
                queue.reinsert(previous.withPayload(queued));   // même rang, même heure d'entrée
                return;
            }
            boolean priority = request.priority() == Priority.PMR || request.priority() == Priority.URGENT;
            queue.add(request.requestId(), queued, priority, SimClock.get().nowSimMin());
            queuedCount++;
            EventReporter.report(ParkingManagerAgent.this, EventTypes.QUEUED, request.requestId(), null,
                    Map.of("priority", request.priority().name()));
            System.out.println("[Manager] " + request.requestId() + " -> MISE EN FILE (zones compatibles pleines, "
                    + "priorité " + request.priority() + ", " + queue.size() + " en attente)");
        }

        @Override
        public void allocated(Allocation a, ManagerCnpInitiator.AllocationInfo info) {
            inFlight.remove(a.requestId());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("zoneId", a.zoneId());
            details.put("slotId", a.slotId());
            details.put("price", a.finalPrice());
            details.put("score", a.score());
            details.put("startSimMin", a.startSimMin());
            details.put("endSimMin", a.endSimMin());
            details.put("wasteful", info.wasteful());
            details.put("distanceToExit", info.distanceToExit());
            details.put("nearExit", info.nearExitRequested());
            EventReporter.report(ParkingManagerAgent.this, EventTypes.ALLOCATED, a.requestId(), a.zoneId(), details);

            Double busyUntil = slotOccupiedUntil.get(a.slotId());
            if (busyUntil != null && busyUntil > a.startSimMin() + 1e-6) {
                doubleAllocations++;
                System.out.println("[Manager] !!! DOUBLE ATTRIBUTION détectée sur " + a.slotId());
            }
            slotOccupiedUntil.put(a.slotId(), a.endSimMin());
            endsByZone.computeIfAbsent(a.zoneId(), z -> new ArrayList<>()).add(a.endSimMin());
            allocatedCount++;
            revenue += a.finalPrice();
            System.out.println(String.format(Locale.ROOT,
                    "[Manager] %s -> %s, place %s, %.2f DT, score %.3f", a.requestId(), a.zoneId(),
                    a.slotId(), a.finalPrice(), a.score()));
            printTally();
        }

        @Override
        public void refused(String requestId, RefusalReason reason) {
            inFlight.remove(requestId);
            refusedCount++;
            EventReporter.report(ParkingManagerAgent.this, EventTypes.REFUSED, requestId, null,
                    Map.of("reason", reason.name()));
            System.out.println("[Manager] " + requestId + " -> REFUSÉE (" + reason + ")");
            printTally();
        }

        @Override
        public void failed(String requestId, String why) {
            inFlight.remove(requestId);
            failedCount++;
            EventReporter.report(ParkingManagerAgent.this, EventTypes.FAILED, requestId, null,
                    Map.of("reason", why));
            System.out.println("[Manager] " + requestId + " -> ÉCHEC (" + why + ")");
            printTally();
        }
    }
}