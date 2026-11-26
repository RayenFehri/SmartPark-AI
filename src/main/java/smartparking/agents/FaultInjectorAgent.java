package smartparking.agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.WakerBehaviour;
import jade.lang.acl.ACLMessage;
import smartparking.models.Scenario;
import smartparking.services.SimClock;
import smartparking.utils.Ontology;

import java.util.List;
import java.util.Locale;

/**
 * Injecteur de pannes : joue la liste des pannes du scénario aux instants simulés prévus.
 * Argument de démarrage : List&lt;Scenario.FaultEvent&gt;. À créer APRÈS le démarrage de l'horloge de simulation.
 *
 * Pour chaque panne : INFORM FAULT_ON à la zone à l'instant de début, FAULT_OFF à la fin
 * (ontologie smartpark-control). La zone se comporte alors comme un agent planté : plus de réponse, plus de signe de vie.
 */
public class FaultInjectorAgent extends Agent {

    @Override
    @SuppressWarnings("unchecked")
    protected void setup() {
        Object[] args = getArguments();
        if (args == null || args.length < 1 || !(args[0] instanceof List)) {
            System.err.println("[injector] erreur : liste de pannes manquante");
            doDelete();
            return;
        }
        for (Scenario.FaultEvent fault : (List<Scenario.FaultEvent>) args[0]) {
            schedule(fault.atSimMin(), fault.zoneId(), Ontology.FAULT_ON);
            schedule(fault.atSimMin() + fault.durationSimMin(), fault.zoneId(), Ontology.FAULT_OFF);
            System.out.println(String.format(Locale.ROOT, "[injector] panne prévue : %s de t = %.0f à t = %.0f min",
                    fault.zoneId(), fault.atSimMin(), fault.atSimMin() + fault.durationSimMin()));
        }
    }

    private void schedule(double simMin, String zoneId, String command) {
        addBehaviour(new WakerBehaviour(this, SimClock.get().realMsUntil(simMin)) {
            @Override
            protected void onWake() {
                ACLMessage message = new ACLMessage(ACLMessage.INFORM);
                message.addReceiver(new AID(zoneId, AID.ISLOCALNAME));
                message.setOntology(Ontology.CONTROL);
                message.setContent(command);
                myAgent.send(message);
                System.out.println(String.format(Locale.ROOT, "[injector] t = %.0f min : %s -> %s",
                        SimClock.get().nowSimMin(), command, zoneId));
            }
        });
    }
}