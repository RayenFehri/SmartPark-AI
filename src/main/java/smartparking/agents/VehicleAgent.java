package smartparking.agents;

import com.google.gson.JsonSyntaxException;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.proto.AchieveREInitiator;
import smartparking.models.Allocation;
import smartparking.models.ParkingRequest;
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
    private static final String MANAGER_NAME = "manager";

    private ParkingRequest request;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args == null || args.length < 1 || !(args[0] instanceof ParkingRequest)) {
            System.err.println("[" + getLocalName() + "] erreur : demande de stationnement manquante");
            doDelete();
            return;
        }
        request = (ParkingRequest) args[0];

        long delayMs = SimClock.get().realMsUntil(request.arrivalSimMin());
        addBehaviour(new WakerBehaviour(this, delayMs) {
            @Override
            protected void onWake() {
                sendRequest();
            }
        });
    }

    private void sendRequest() {
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

        long stayMs = SimClock.get().realMsUntil(allocation.endSimMin());
        addBehaviour(new WakerBehaviour(this, stayMs) {
            @Override
            protected void onWake() {
                log("je pars, place " + allocation.slotId() + " rendue");
                doDelete();
            }
        });
    }

    private void log(String text) {
        System.out.println("[" + getLocalName() + "] " + text);
    }
}