package smartparking.behaviours;

import com.google.gson.JsonSyntaxException;
import jade.core.AID;
import jade.core.Agent;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.proto.ContractNetInitiator;
import smartparking.models.Allocation;
import smartparking.models.DecisionTrace;
import smartparking.models.ParkingRequest;
import smartparking.models.Proposal;
import smartparking.models.Refusal;
import smartparking.models.RefusalReason;
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

    /** Ce que le Manager veut savoir du résultat de chaque négociation. */
    public interface Outcomes {
        void trace(DecisionTrace trace);

        void allocated(Allocation allocation);

        void refused(String requestId, RefusalReason reason);

        void failed(String requestId, String why);
    }

    /** Services partagés par toutes les négociations du Manager. */
    public record Deps(AllocationScorer scorer,
                       Supplier<List<AID>> zoneFinder,
                       Supplier<Set<String>> downZones,
                       Outcomes outcomes,
                       long cnpTimeoutMs) { }

    private static final int MAX_ATTEMPTS = 2;

    private final Deps deps;
    private final ParkingRequest request;
    private final ACLMessage vehicleRequest;
    private final int attempt;

    private Proposal best;
    private double bestScore;
    private boolean accepted;
    private boolean finished;

    private ManagerCnpInitiator(Agent agent, ACLMessage cfp, Deps deps, ParkingRequest request,
                                ACLMessage vehicleRequest, int attempt) {
        super(agent, cfp);
        this.deps = deps;
        this.request = request;
        this.vehicleRequest = vehicleRequest;
        this.attempt = attempt;
    }

    /** Lance une négociation (tentative 1 ou 2). Répond tout de suite au véhicule s'il n'y a aucune zone. */
    public static void launch(Agent agent, Deps deps, ParkingRequest request, ACLMessage vehicleRequest,
                              int attempt) {
        List<AID> zones = deps.zoneFinder().get();
        if (zones.isEmpty()) {
            sendToVehicle(agent, vehicleRequest, ACLMessage.REFUSE,
                    JsonUtil.toJson(new Refusal(request.requestId(), null, RefusalReason.NO_SLOT)));
            deps.outcomes().refused(request.requestId(), RefusalReason.NO_SLOT);
            return;
        }

        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        cfp.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);
        cfp.setOntology(Ontology.NAME);
        cfp.setLanguage(Ontology.LANGUAGE);
        // une conversation distincte par tentative, pour ne jamais mélanger deux négociations
        cfp.setConversationId(attempt == 1 ? request.requestId() : request.requestId() + "#" + attempt);
        cfp.setContent(JsonUtil.toJson(request));
        cfp.setReplyByDate(new Date(System.currentTimeMillis() + deps.cnpTimeoutMs()));
        for (AID zone : zones) {
            cfp.addReceiver(zone);
        }
        agent.addBehaviour(new ManagerCnpInitiator(agent, cfp, deps, request, vehicleRequest, attempt));
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

        AllocationScorer.Result result = deps.scorer().evaluate(request, proposals, refusals,
                deps.downZones().get());
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
            sendToVehicle(myAgent, vehicleRequest, ACLMessage.REFUSE,
                    JsonUtil.toJson(new Refusal(request.requestId(), null, reason)));
            deps.outcomes().refused(request.requestId(), reason);
            finished = true;
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
            deps.outcomes().allocated(allocation);
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
        if (attempt < MAX_ATTEMPTS) {
            log("ÉCHEC de " + failure.getSender().getLocalName() + " (" + failure.getContent()
                    + ") -> nouvelle négociation");
            launch(myAgent, deps, request, vehicleRequest, attempt + 1);
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