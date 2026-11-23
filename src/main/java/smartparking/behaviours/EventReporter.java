package smartparking.behaviours;

import jade.core.AID;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import smartparking.models.Event;
import smartparking.services.SimClock;
import smartparking.utils.JsonUtil;
import smartparking.utils.Ontology;

/**
 * Envoie un Event au MonitorAgent (INFORM, contenu JSON). Utilisé par le Manager et les zones.
 * Le paramètre details peut être un texte JSON déjà prêt ou n'importe quel objet (sérialisé avec Gson).
 */
public final class EventReporter {

    public static final String MONITOR_NAME = "monitor";

    private EventReporter() { }

    public static void report(Agent agent, String type, String requestId, String zoneId, Object details) {
        String detailsJson = details == null ? null
                : details instanceof String text ? text : JsonUtil.toJson(details);
        Event event = new Event(System.currentTimeMillis(), SimClock.get().nowSimMin(), type,
                agent.getLocalName(), requestId, zoneId, detailsJson);

        ACLMessage message = new ACLMessage(ACLMessage.INFORM);
        message.addReceiver(new AID(MONITOR_NAME, AID.ISLOCALNAME));
        message.setOntology(Ontology.NAME);
        message.setLanguage(Ontology.LANGUAGE);
        message.setContent(JsonUtil.toJson(event));
        agent.send(message);
    }
}