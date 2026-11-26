package smartparking.behaviours;

import com.google.gson.JsonSyntaxException;
import jade.core.Agent;
import jade.domain.FIPAAgentManagement.FailureException;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.proto.SSContractNetResponder;
import jade.proto.SSResponderDispatcher;
import smartparking.models.ParkingRequest;
import smartparking.services.SimClock;
import smartparking.services.ZoneState;
import smartparking.utils.JsonUtil;
import smartparking.utils.Ontology;

import java.util.Date;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Côté « zone » du protocole Contract Net : répond aux appels d'offres (CFP),
 * confirme ou libère la place selon la décision du Manager.
 * Toute la logique métier est dans ZoneState ; cette classe ne fait que traduire
 * les messages JADE en appels de méthodes (et inversement).
 *
 * Un dispatcher crée une session par CFP : les négociations de plusieurs véhicules sont donc traitées
 * en parallèle (un ContractNetResponder simple n'en traite qu'une à la fois et bloque les autres tant
 * que le Manager n'a pas répondu). Pendant une panne injectée (isDown), les CFP restent sans réponse
 * et un ACCEPT reçoit FAILURE.
 */
public class ZoneCnpResponder extends SSResponderDispatcher {

    private final ZoneState zone;
    private final long holdTtlMs;
    private final String name;
    private final Runnable onStateChange;
    private final Runnable onSlotReleased;
    private final BooleanSupplier isDown;

    /**
     * onStateChange est appelé après chaque changement d'état des places (hold, confirmation, libération) ;
     * onSlotReleased quand une place réservée redevient libre (REJECT_PROPOSAL).
     */
    public ZoneCnpResponder(Agent agent, ZoneState zone, long holdTtlMs, Runnable onStateChange,
                            Runnable onSlotReleased, BooleanSupplier isDown) {
        super(agent, template());
        this.isDown = isDown;
        this.zone = zone;
        this.holdTtlMs = holdTtlMs;
        this.onStateChange = onStateChange;
        this.onSlotReleased = onSlotReleased;
        this.name = zone.getZoneId();
    }

    /** Ne traite que les CFP du protocole Contract Net. */
    private static MessageTemplate template() {
        return MessageTemplate.and(
                MessageTemplate.MatchProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET),
                MessageTemplate.MatchPerformative(ACLMessage.CFP));
    }

    /** Une session par CFP ; pendant une panne, personne ne répond. */
    @Override
    protected Behaviour createResponder(ACLMessage cfp) {
        if (isDown.getAsBoolean()) {
            return new OneShotBehaviour(myAgent) {
                @Override
                public void action() {
                    // zone en panne : pas de réponse
                }
            };
        }
        return new Session(myAgent, cfp);
    }

    /** Session de négociation d'un CFP. */
    private class Session extends SSContractNetResponder {
        Session(Agent agent, ACLMessage cfp) {
            super(agent, cfp);
        }

        @Override
        protected ACLMessage handleCfp(ACLMessage cfp) {
            return onCfp(cfp);
        }

        @Override
        protected ACLMessage handleAcceptProposal(ACLMessage cfp, ACLMessage propose, ACLMessage accept)
                throws FailureException {
            return onAccept(cfp, accept);
        }

        @Override
        protected void handleRejectProposal(ACLMessage cfp, ACLMessage propose, ACLMessage reject) {
            onReject(cfp);
        }
    }

    /** Appel d'offres reçu : PROPOSE (place mise de côté) ou REFUSE (avec le motif). */
    private ACLMessage onCfp(ACLMessage cfp) {
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
            // sans réponse du Manager avant la fin de la réservation, la session se termine d'elle-même
            reply.setReplyByDate(new Date(System.currentTimeMillis() + holdTtlMs));
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
    private ACLMessage onAccept(ACLMessage cfp, ACLMessage accept) throws FailureException {
        if (isDown.getAsBoolean()) {
            throw new FailureException("ZONE_DOWN");   // la zone a planté entre le PROPOSE et l'ACCEPT
        }
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
    private void onReject(ACLMessage cfp) {
        if (isDown.getAsBoolean()) {
            return;
        }
        ParkingRequest request = readRequest(cfp);
        boolean released = zone.release(request.requestId());
        log(String.format("REJECT %s -> %s", request.requestId(),
                released ? "place libérée" : "rien à libérer (déjà expirée)"));
        onStateChange.run();
        if (released) {
            onSlotReleased.run();
        }
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