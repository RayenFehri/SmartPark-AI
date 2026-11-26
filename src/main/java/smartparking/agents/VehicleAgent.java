package smartparking.agents;

import com.google.gson.JsonSyntaxException;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.AchieveREInitiator;
import smartparking.models.Allocation;
import smartparking.models.ExplainReply;
import smartparking.models.NlParseReply;
import smartparking.models.ParkingRequest;
import smartparking.models.Strategy;
import smartparking.services.NlSentenceGenerator;
import smartparking.services.SimClock;
import smartparking.utils.JsonUtil;
import smartparking.utils.Ontology;

/**
 * Véhicule : porte une demande de stationnement, l'envoie au Manager à son heure d'arrivée
 * (temps simulé) en FIPA-Request, attend la décision, puis repart à la fin de son stationnement.
 * Argument de démarrage : la ParkingRequest (objet Java, même JVM que le Launcher).
 */
public class VehicleAgent extends Agent {

    private static final long REPLY_TIMEOUT_MS = 10_000;
    private static final long ASSISTANT_TIMEOUT_MS = 8_000;
    private static final String MANAGER_NAME = "manager";

    private ParkingRequest request;
    /** Stratégie C : le conducteur s'exprime en langage naturel et l'assistant comprend sa demande. */
    private boolean naturalLanguage;
    /** Option --explain : après l'attribution, le conducteur demande à l'assistant pourquoi cette place. */
    private boolean askExplanation;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args == null || args.length < 1 || !(args[0] instanceof ParkingRequest)) {
            System.err.println("[" + getLocalName() + "] erreur : demande de stationnement manquante");
            doDelete();
            return;
        }
        request = (ParkingRequest) args[0];
        naturalLanguage = Strategy.CNP_LLM.name().equals(System.getProperty("smartpark.strategy"));
        askExplanation = naturalLanguage && Boolean.getBoolean("smartpark.explain");

        long delayMs = SimClock.get().realMsUntil(request.arrivalSimMin());
        addBehaviour(new WakerBehaviour(this, delayMs) {
            @Override
            protected void onWake() {
                if (naturalLanguage) {
                    askAssistant();
                } else {
                    sendRequest(request);
                }
            }
        });
    }

    /**
     * Stratégie C : envoie la phrase à l'assistant et attend la demande comprise. Si l'assistant ne répond pas à temps
     * ou ne comprend pas, la demande structurée d'origine est envoyée telle quelle (le véhicule ne reste pas bloqué).
     */
    private void askAssistant() {
        String sentence = NlSentenceGenerator.sentence(request);
        String conversation = request.requestId() + "-nl";
        com.google.gson.JsonObject content = new com.google.gson.JsonObject();
        content.addProperty("kind", "PARSE");
        content.addProperty("requestId", request.requestId());
        content.addProperty("text", sentence);

        ACLMessage ask = new ACLMessage(ACLMessage.REQUEST);
        ask.setOntology(Ontology.NL_ASSIST);
        ask.setLanguage(Ontology.LANGUAGE);
        ask.setConversationId(conversation);
        ask.addReceiver(new AID(LLMAssistantAgent.ASSISTANT_NAME, AID.ISLOCALNAME));
        ask.setContent(content.toString());
        send(ask);
        log("dit à l'assistant : « " + sentence + " »");

        boolean[] answered = {false};
        addBehaviour(new CyclicBehaviour(this) {
            private final MessageTemplate template = MessageTemplate.and(
                    MessageTemplate.MatchOntology(Ontology.NL_ASSIST),
                    MessageTemplate.MatchConversationId(conversation));

            @Override
            public void action() {
                ACLMessage reply = myAgent.receive(template);
                if (reply == null) {
                    block();
                    return;
                }
                if (answered[0]) {
                    return;
                }
                answered[0] = true;
                if (reply.getPerformative() != ACLMessage.INFORM) {
                    log("l'assistant n'a pas compris (" + reply.getContent() + ") : demande structurée envoyée");
                    sendRequest(request);
                    return;
                }
                try {
                    NlParseReply understood = JsonUtil.fromJson(reply.getContent(), NlParseReply.class);
                    ParkingRequest parsed = understood.request().withIds(request.requestId(), request.vehicleId())
                            .withArrival(request.arrivalSimMin());
                    log("compris par l'assistant (" + understood.source() + ", " + understood.latencyMs() + " ms)");
                    sendRequest(parsed);
                } catch (RuntimeException e) {
                    log("réponse de l'assistant illisible : demande structurée envoyée");
                    sendRequest(request);
                }
            }
        });
        addBehaviour(new WakerBehaviour(this, ASSISTANT_TIMEOUT_MS) {
            @Override
            protected void onWake() {
                if (!answered[0]) {
                    answered[0] = true;
                    log("l'assistant ne répond pas : demande structurée envoyée");
                    sendRequest(request);
                }
            }
        });
    }

    /** Envoie au Manager la demande (structurée, ou comprise par l'assistant en stratégie C). */
    private void sendRequest(ParkingRequest request) {
        ACLMessage message = new ACLMessage(ACLMessage.REQUEST);
        message.setProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST);
        message.setOntology(Ontology.NAME);
        message.setLanguage(Ontology.LANGUAGE);
        message.setConversationId(request.requestId());
        message.addReceiver(new AID(MANAGER_NAME, AID.ISLOCALNAME));
        message.setContent(JsonUtil.toJson(request));
        message.setReplyByDate(new java.util.Date(System.currentTimeMillis() + REPLY_TIMEOUT_MS));

        log("arrivée (t = " + request.arrivalSimMin() + " min) : " + request.duration() + " h, budget "
                + request.maxBudget() + " DT, " + request.vehicleType());

        addBehaviour(new AchieveREInitiator(this, message) {
            @Override
            protected void handleAgree(ACLMessage agree) {
                log("demande acceptée par le Manager, négociation en cours");
            }

            @Override
            protected void handleInform(ACLMessage inform) {
                onAllocated(inform);
            }

            @Override
            protected void handleRefuse(ACLMessage refuse) {
                log("demande REFUSÉE " + refuse.getContent());
                doDelete();
            }

            @Override
            protected void handleFailure(ACLMessage failure) {
                log("ÉCHEC " + failure.getContent());
                doDelete();
            }

            /**
             * Le Manager répond AGREE puis REFUSE (cahier des charges, §8). Pour JADE, un REFUSE après un AGREE est
             * hors séquence FIPA-Request : sans cette méthode, le refus serait ignoré et le véhicule attendrait
             * jusqu'au délai de 10 s.
             */
            @Override
            protected void handleOutOfSequence(ACLMessage message) {
                if (message.getPerformative() == ACLMessage.REFUSE) {
                    log("demande REFUSÉE " + message.getContent());
                    doDelete();
                }
            }

            @Override
            protected void handleNotUnderstood(ACLMessage notUnderstood) {
                log("demande non comprise par le Manager");
                doDelete();
            }
        });
    }

    private void onAllocated(ACLMessage inform) {
        Allocation allocation;
        try {
            allocation = JsonUtil.fromJson(inform.getContent(), Allocation.class);
        } catch (JsonSyntaxException e) {
            log("réponse illisible du Manager");
            doDelete();
            return;
        }
        log(String.format(java.util.Locale.ROOT, "place %s attribuée (%s), %.2f DT, jusqu'à t = %.0f min",
                allocation.slotId(), allocation.zoneId(), allocation.finalPrice(), allocation.endSimMin()));

        if (askExplanation) {
            askExplanation();
        }
        long stayMs = SimClock.get().realMsUntil(allocation.endSimMin());
        addBehaviour(new WakerBehaviour(this, stayMs) {
            @Override
            protected void onWake() {
                log("je pars, place " + allocation.slotId() + " rendue");
                doDelete();
            }
        });
    }

    /** Demande à l'assistant pourquoi cette place a été choisie (boucle ReAct côté assistant). */
    private void askExplanation() {
        String conversation = request.requestId() + "-ex";
        com.google.gson.JsonObject content = new com.google.gson.JsonObject();
        content.addProperty("kind", "EXPLAIN");
        content.addProperty("requestId", request.requestId());
        content.addProperty("question", "Pourquoi cette place m'a-t-elle été attribuée ?");
        ACLMessage ask = new ACLMessage(ACLMessage.REQUEST);
        ask.setOntology(Ontology.NL_ASSIST);
        ask.setLanguage(Ontology.LANGUAGE);
        ask.setConversationId(conversation);
        ask.addReceiver(new AID(LLMAssistantAgent.ASSISTANT_NAME, AID.ISLOCALNAME));
        ask.setContent(content.toString());
        send(ask);
        addBehaviour(new CyclicBehaviour(this) {
            private final MessageTemplate template = MessageTemplate.and(
                    MessageTemplate.MatchOntology(Ontology.NL_ASSIST),
                    MessageTemplate.MatchConversationId(conversation));

            @Override
            public void action() {
                ACLMessage reply = myAgent.receive(template);
                if (reply == null) {
                    block();
                    return;
                }
                if (reply.getPerformative() == ACLMessage.INFORM) {
                    ExplainReply explanation = JsonUtil.fromJson(reply.getContent(), ExplainReply.class);
                    // journalisée par l'assistant (le véhicule peut être reparti avant la réponse)
                } else {
                    log("pas d'explication : " + reply.getContent());
                }
                myAgent.removeBehaviour(this);
            }
        });
    }

    private void log(String text) {
        System.out.println("[" + getLocalName() + "] " + text);
    }
}
