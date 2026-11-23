package smartparking.behaviours;

import com.google.gson.JsonSyntaxException;
import jade.core.Agent;
import jade.domain.FIPAAgentManagement.FailureException;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.ContractNetResponder;
import smartparking.models.ParkingRequest;
import smartparking.services.SimClock;
import smartparking.services.ZoneState;
import smartparking.utils.JsonUtil;
import smartparking.utils.Ontology;

import java.util.Optional;

/**
 * Côté « zone » du protocole Contract Net : répond aux appels d'offres (CFP),
 * confirme ou libère la place selon la décision du Manager.
 * Toute la logique métier est dans ZoneState ; cette classe ne fait que traduire
 * les messages JADE en appels de méthodes (et inversement).
 */
public class ZoneCnpResponder extends ContractNetResponder {

    private final ZoneState zone;
    private final long holdTtlMs;
    private final String name;
    private final Runnable onStateChange;

    /** onStateChange est appelé après chaque changement d'état des places (hold, confirmation, libération). */
    public ZoneCnpResponder(Agent agent, ZoneState zone, long holdTtlMs, Runnable onStateChange) {
        super(agent, template());
        this.zone = zone;
        this.holdTtlMs = holdTtlMs;
        this.onStateChange = onStateChange;
        this.name = zone.getZoneId();
    }

    /** Ne traite que les CFP du protocole Contract Net. */
    private static MessageTemplate template() {
        return MessageTemplate.and(
                MessageTemplate.MatchProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET),
                MessageTemplate.MatchPerformative(ACLMessage.CFP));
    }

    /** Appel d'offres reçu : PROPOSE (place mise de côté) ou REFUSE (avec le motif). */
    @Override
    protected ACLMessage handleCfp(ACLMessage cfp) {
        ACLMessage reply = cfp.createReply();
        reply.setOntology(Ontology.NAME);
        reply.setLanguage(Ontology.LANGUAGE);

        ParkingRequest request = readRequest(cfp);
        if (request == null || request.requestId() == null) {
            reply.setPerformative(ACLMessage.NOT_UNDERSTOOD);
            reply.setContent("demande illisible");
            log("CFP illisible -> NOT_UNDERSTOOD");
            return reply;
        }

        ZoneState.CfpOutcome outcome = zone.handleCfp(request, System.currentTimeMillis(), holdTtlMs);
        if (outcome.isProposal()) {
            reply.setPerformative(ACLMessage.PROPOSE);
            reply.setContent(JsonUtil.toJson(outcome.proposal()));
            log(String.format("CFP %s -> PROPOSE %s, prix %.2f, occupation %.0f %%",
                    request.requestId(), outcome.proposal().slotId(), outcome.proposal().totalPrice(),
                    outcome.proposal().occupancyRate() * 100));
            onStateChange.run();
        } else {
            reply.setPerformative(ACLMessage.REFUSE);
            reply.setContent(JsonUtil.toJson(outcome.refusal()));
            log(String.format("CFP %s -> REFUSE (%s)", request.requestId(), outcome.refusal().reason()));
        }
        return reply;
    }

    /** Proposition acceptée : la place devient OCCUPIED, sinon FAILURE (réservation expirée). */
    @Override
    protected ACLMessage handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept)
            throws FailureException {
        ParkingRequest request = readRequest(cfp);
        Optional<ZoneState.Confirmation> confirmation = zone.confirm(
                request.requestId(), System.currentTimeMillis(), SimClock.get().nowSimMin());

        if (confirmation.isEmpty()) {
            log(String.format("ACCEPT %s -> CONFLIT : la réservation n'existe plus", request.requestId()));
            throw new FailureException("HOLD_EXPIRED");
        }

        ZoneState.Confirmation c = confirmation.get();
        log(String.format("ACCEPT %s -> place %s OCCUPEE jusqu'à %.0f min simulées",
                request.requestId(), c.slotId(), c.endSimMin()));
        ACLMessage inform = accept.createReply();
        inform.setPerformative(ACLMessage.INFORM);
        inform.setOntology(Ontology.NAME);
        inform.setLanguage(Ontology.LANGUAGE);
        inform.setContent(JsonUtil.toJson(c));
        onStateChange.run();
        return inform;
    }

    /** Proposition refusée : la place mise de côté est libérée tout de suite. */
    @Override
    protected void handleRejectProposal(ACLMessage cfp, ACLMessage propose, ACLMessage reject) {
        ParkingRequest request = readRequest(cfp);
        boolean released = zone.release(request.requestId());
        log(String.format("REJECT %s -> %s", request.requestId(),
                released ? "place libérée" : "rien à libérer (déjà expirée)"));
        onStateChange.run();
    }

    private ParkingRequest readRequest(ACLMessage message) {
        try {
            return JsonUtil.fromJson(message.getContent(), ParkingRequest.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    private void log(String text) {
        System.out.println("[" + name + "] " + text);
    }
}