package smartparking.behaviours;

import com.google.gson.JsonSyntaxException;
import jade.core.AID;
import jade.core.Agent;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.proto.ContractNetInitiator;
import smartparking.models.Allocation;
import smartparking.models.Alternative;
import smartparking.models.DecisionTrace;
import smartparking.models.ParkingRequest;
import smartparking.models.Proposal;
import smartparking.models.Refusal;
import smartparking.models.RefusalNotice;
import smartparking.models.RefusalReason;
import smartparking.models.Strategy;
import smartparking.services.AllocationScorer;
import smartparking.services.ZoneState;
import smartparking.utils.JsonUtil;
import smartparking.utils.Ontology;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * Côté « Manager » du protocole Contract Net : une instance par demande de véhicule.
 *
 * Déroulement : CFP à toutes les zones actives -> réception des PROPOSE / REFUSE ->
 * AllocationScorer (filtre + score) -> ACCEPT_PROPOSAL au meilleur, REJECT_PROPOSAL aux autres ->
 * INFORM de la zone -> INFORM (Allocation) au véhicule.
 * Si la zone répond FAILURE (réservation expirée), le CNP est relancé une seule fois.
 */
public class ManagerCnpInitiator extends ContractNetInitiator {

    /** Informations sur la place attribuée, pour les métriques. */
    public record AllocationInfo(boolean wasteful, double distanceToExit, boolean nearExitRequested) { }

    /** Ce que le Manager veut savoir du résultat de chaque négociation. */
    public interface Outcomes {
        void trace(DecisionTrace trace);

        /** info.wasteful : zone spécialisée attribuée à un véhicule qui n'en avait pas besoin. */
        void allocated(Allocation allocation, AllocationInfo info);

        /** Toutes les zones compatibles sont pleines : le Manager met la demande en file d'attente (EF10). */
        void queued(ParkingRequest request, ACLMessage vehicleRequest, int round);

        /** Alternatives à proposer au véhicule dans un refus (autre durée, estimation d'attente). */
        List<Alternative> alternatives(ParkingRequest request, RefusalReason reason);

        /** Un ACCEPT_PROPOSAL a échoué : la place n'était plus réservée à ce véhicule (§10). */
        void conflict(String requestId, String zoneId);

        void refused(String requestId, RefusalReason reason);

        void failed(String requestId, String why);
    }

    /** Services partagés par toutes les négociations du Manager. */
    public record Deps(Strategy strategy,
                       AllocationScorer scorer,
                       Supplier<List<AID>> zoneFinder,
                       Supplier<Set<String>> downZones,
                       Outcomes outcomes,
                       long cnpTimeoutMs,
                       boolean queueEnabled,
                       BiPredicate<String, ParkingRequest> zoneCanServe) { }

    private static final int MAX_ATTEMPTS = 2;

    private final Deps deps;
    private final ParkingRequest request;
    private final ACLMessage vehicleRequest;
    private final int attempt;
    /** 0 pour la première négociation ; augmente à chaque relance depuis la file d'attente. */
    private final int round;
    /** Zones interrogées : une zone qui ne répond pas avant le délai est considérée comme indisponible. */
    private final List<String> contactedZones;
    /** Vrai si une zone compatible était déjà connue en panne au lancement (donc absente de ce CFP). */
    private final boolean downAtLaunch;

    private Proposal best;
    private double bestScore;
    private boolean accepted;
    private boolean finished;

    private ManagerCnpInitiator(Agent agent, ACLMessage cfp, Deps deps, ParkingRequest request,
                                ACLMessage vehicleRequest, int attempt, int round, List<String> contactedZones,
                                boolean downAtLaunch) {
        super(agent, cfp);
        this.deps = deps;
        this.request = request;
        this.vehicleRequest = vehicleRequest;
        this.attempt = attempt;
        this.round = round;
        this.contactedZones = contactedZones;
        this.downAtLaunch = downAtLaunch;
    }

    /** Négociation initiale d'une demande (tentative 1, pas encore passée par la file d'attente). */
    public static void launch(Agent agent, Deps deps, ParkingRequest request, ACLMessage vehicleRequest,
                              int attempt) {
        launch(agent, deps, request, vehicleRequest, attempt, 0);
    }

    /**
     * Lance une négociation (tentative 1 ou 2 ; round = nombre de relances depuis la file d'attente).
     * Répond tout de suite au véhicule s'il n'y a aucune zone active.
     */
    public static void launch(Agent agent, Deps deps, ParkingRequest request, ACLMessage vehicleRequest,
                              int attempt, int round) {
        List<AID> zones = deps.zoneFinder().get();
        if (zones.isEmpty()) {
            if (deps.queueEnabled() && deps.downZones().get().stream()
                    .anyMatch(z -> deps.zoneCanServe().test(z, request))) {
                // toutes les zones sont en panne : la demande attend leur retour
                deps.outcomes().queued(request, vehicleRequest, round);
            } else {
                refuse(agent, deps, request, vehicleRequest, RefusalReason.NO_SLOT);
            }
            return;
        }

        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        cfp.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);
        cfp.setOntology(Ontology.NAME);
        cfp.setLanguage(Ontology.LANGUAGE);
        // une conversation distincte par tentative et par relance, pour ne jamais mélanger deux négociations
        cfp.setConversationId(request.requestId() + (round > 0 ? "@r" + round : "")
                + (attempt > 1 ? "#" + attempt : ""));
        cfp.setContent(JsonUtil.toJson(request));
        cfp.setReplyByDate(new Date(System.currentTimeMillis() + deps.cnpTimeoutMs()));
        for (AID zone : zones) {
            cfp.addReceiver(zone);
        }
        agent.addBehaviour(new ManagerCnpInitiator(agent, cfp, deps, request, vehicleRequest, attempt, round,
                zones.stream().map(AID::getLocalName).toList(),
                deps.downZones().get().stream().anyMatch(z -> deps.zoneCanServe().test(z, request))));
    }

    /** REFUSE vers le véhicule, avec le motif et des alternatives (§8), puis information du Manager. */
    public static void refuse(Agent agent, Deps deps, ParkingRequest request, ACLMessage vehicleRequest,
                              RefusalReason reason) {
        RefusalNotice notice = new RefusalNotice(request.requestId(), reason,
                deps.outcomes().alternatives(request, reason));
        sendToVehicle(agent, vehicleRequest, ACLMessage.REFUSE, JsonUtil.toJson(notice));
        deps.outcomes().refused(request.requestId(), reason);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected void handleAllResponses(Vector responses, Vector acceptances) {
        List<Proposal> proposals = new ArrayList<>();
        Map<Proposal, ACLMessage> proposalMessages = new IdentityHashMap<>();
        List<Refusal> refusals = new ArrayList<>();

        for (Object o : responses) {
            ACLMessage message = (ACLMessage) o;
            try {
                if (message.getPerformative() == ACLMessage.PROPOSE) {
                    Proposal proposal = JsonUtil.fromJson(message.getContent(), Proposal.class);
                    proposals.add(proposal);
                    proposalMessages.put(proposal, message);
                } else if (message.getPerformative() == ACLMessage.REFUSE) {
                    refusals.add(JsonUtil.fromJson(message.getContent(), Refusal.class));
                }
            } catch (JsonSyntaxException e) {
                log("réponse illisible de " + message.getSender().getLocalName() + " : ignorée");
            }
        }

        AllocationScorer.Result result = deps.strategy() == Strategy.FIRST_AVAILABLE
                ? deps.scorer().evaluateFirstAvailable(request, proposals, refusals, deps.downZones().get())
                : deps.scorer().evaluate(request, proposals, refusals, deps.downZones().get());
        deps.outcomes().trace(result.trace());
        log(result.trace().summary());

        // toute proposition reçue doit recevoir une réponse : ACCEPT pour la meilleure, REJECT pour les autres
        for (Proposal proposal : proposals) {
            ACLMessage answer = proposalMessages.get(proposal).createReply();
            answer.setOntology(Ontology.NAME);
            answer.setLanguage(Ontology.LANGUAGE);
            answer.setContent(JsonUtil.toJson(Map.of("requestId", request.requestId(),
                    "slotId", proposal.slotId())));
            if (result.isAllocated() && proposal == result.best()) {
                answer.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                answer.setReplyByDate(new Date(System.currentTimeMillis() + deps.cnpTimeoutMs()));
            } else {
                answer.setPerformative(ACLMessage.REJECT_PROPOSAL);
            }
            acceptances.add(answer);
        }

        if (!result.isAllocated()) {
            RefusalReason reason = refusalReason(proposals, refusals);
            finished = true;
            // mode dégradé : une zone est en panne connue (DOWN) ou n'a pas répondu à temps. La demande peut
            // être servie à son retour, donc elle attend en file au lieu d'être refusée tout de suite.
            // (seules comptent les zones en panne ou silencieuses qui auraient pu servir ce véhicule)
            Set<String> answered = new HashSet<>();
            for (Object o : responses) {
                answered.add(((ACLMessage) o).getSender().getLocalName());
            }
            boolean silentCompatible = contactedZones.stream()
                    .anyMatch(z -> !answered.contains(z) && deps.zoneCanServe().test(z, request));
            boolean downNow = deps.downZones().get().stream().anyMatch(z -> deps.zoneCanServe().test(z, request));
            boolean degraded = downAtLaunch || downNow || silentCompatible;
            boolean waitForZones = degraded && (reason == RefusalReason.NO_SLOT
                    || reason == RefusalReason.BUDGET_EXCEEDED);
            if ((reason == RefusalReason.ZONE_FULL || waitForZones) && deps.queueEnabled()) {
                // saturation : pas de refus immédiat, la demande attend en file (FIFO, PMR prioritaire)
                deps.outcomes().queued(request, vehicleRequest, round);
            } else {
                refuse(myAgent, deps, request, vehicleRequest, reason);
            }
            return;
        }
        best = result.best();
        bestScore = result.bestScore();
        accepted = true;
    }

    /** La zone confirme : la place est OCCUPIED, le véhicule reçoit son Allocation. */
    @Override
    protected void handleInform(ACLMessage inform) {
        if (finished) {
            return;
        }
        try {
            ZoneState.Confirmation c = JsonUtil.fromJson(inform.getContent(), ZoneState.Confirmation.class);
            Allocation allocation = new Allocation(request.requestId(), request.vehicleId(), best.zoneId(),
                    c.slotId(), c.price(), bestScore, c.startSimMin(), c.endSimMin());
            finished = true;
            sendToVehicle(myAgent, vehicleRequest, ACLMessage.INFORM, JsonUtil.toJson(allocation));
            deps.outcomes().allocated(allocation, new AllocationInfo(
                    deps.scorer().wastePenalty(request, best.zoneType()) >= 1.0, best.distanceToExit(),
                    request.preferredLocation() == smartparking.models.PreferredLocation.NEAR_EXIT));
        } catch (JsonSyntaxException e) {
            fail("confirmation illisible de la zone");
        }
    }

    /** La zone échoue (réservation expirée) : une seule relance, puis échec définitif. */
    @Override
    protected void handleFailure(ACLMessage failure) {
        if (!accepted || finished) {
            // échec survenu avant l'ACCEPT (zone injoignable) : la réponse manquante est déjà gérée
            return;
        }
        finished = true;
        deps.outcomes().conflict(request.requestId(), failure.getSender().getLocalName());
        if (attempt < MAX_ATTEMPTS) {
            log("ÉCHEC de " + failure.getSender().getLocalName() + " (" + failure.getContent()
                    + ") -> nouvelle négociation");
            launch(myAgent, deps, request, vehicleRequest, attempt + 1, round);
        } else {
            sendToVehicle(myAgent, vehicleRequest, ACLMessage.FAILURE, "conflit : réservation expirée");
            deps.outcomes().failed(request.requestId(), "conflit après relance");
        }
    }

    /** Filet de sécurité : la zone acceptée n'a jamais répondu avant le délai. */
    @Override
    @SuppressWarnings("rawtypes")
    protected void handleAllResultNotifications(Vector resultNotifications) {
        if (accepted && !finished) {
            fail("la zone n'a pas confirmé à temps");
        }
    }

    private void fail(String why) {
        finished = true;
        sendToVehicle(myAgent, vehicleRequest, ACLMessage.FAILURE, why);
        deps.outcomes().failed(request.requestId(), why);
    }

    /** Motif global du refus : saturation d'abord, puis budget, puis incompatibilité. */
    private static RefusalReason refusalReason(List<Proposal> proposals, List<Refusal> refusals) {
        Set<RefusalReason> reasons = new HashSet<>();
        for (Refusal refusal : refusals) {
            reasons.add(refusal.reason());
        }
        if (reasons.contains(RefusalReason.ZONE_FULL)) {
            return RefusalReason.ZONE_FULL;
        }
        if (reasons.contains(RefusalReason.BUDGET_EXCEEDED) || !proposals.isEmpty()) {
            return RefusalReason.BUDGET_EXCEEDED;
        }
        if (reasons.contains(RefusalReason.INCOMPATIBLE)) {
            return RefusalReason.INCOMPATIBLE;
        }
        return RefusalReason.NO_SLOT;
    }

    private static void sendToVehicle(Agent agent, ACLMessage vehicleRequest, int performative, String content) {
        ACLMessage reply = vehicleRequest.createReply();
        reply.setPerformative(performative);
        reply.setOntology(Ontology.NAME);
        reply.setLanguage(Ontology.LANGUAGE);
        reply.setContent(content);
        agent.send(reply);
    }

    private void log(String text) {
        System.out.println("[Manager] " + text);
    }
}