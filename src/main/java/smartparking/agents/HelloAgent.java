package smartparking.agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

/**
 * Agent de test de l'étape 0 : vérifie le démarrage de JADE, l'enregistrement
 * au DF et l'échange d'un message ACL (l'agent s'écrit à lui-même).
 * Il sera supprimé une fois les vrais agents en place.
 */
public class HelloAgent extends Agent {

    @Override
    protected void setup() {
        System.out.println("[" + getLocalName() + "] démarré, AID = " + getAID().getName());
        registerInDf();
        addBehaviour(new ReceiverBehaviour(this));
        addBehaviour(new OneShotBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                msg.addReceiver(myAgent.getAID());
                msg.setOntology("smartpark-ontology");
                msg.setLanguage("JSON");
                msg.setContent("{\"hello\":\"world\"}");
                myAgent.send(msg);
                System.out.println("[" + myAgent.getLocalName() + "] message envoyé");
            }
        });
    }

    private void registerInDf() {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription sd = new ServiceDescription();
            sd.setType("hello-service");
            sd.setName(getLocalName());
            dfd.addServices(sd);
            DFService.register(this, dfd);
            System.out.println("[" + getLocalName() + "] enregistré au DF (hello-service)");
        } catch (FIPAException e) {
            System.err.println("[" + getLocalName() + "] échec d'enregistrement DF : " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            // ignoré : l'agent s'arrête
        }
        System.out.println("[" + getLocalName() + "] arrêté");
    }

    /** Reçoit les INFORM de l'ontologie smartpark et les affiche. */
    private static class ReceiverBehaviour extends CyclicBehaviour {
        private final MessageTemplate template = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.INFORM),
                MessageTemplate.MatchOntology("smartpark-ontology"));

        ReceiverBehaviour(Agent a) {
            super(a);
        }

        @Override
        public void action() {
            ACLMessage msg = myAgent.receive(template);
            if (msg != null) {
                System.out.println("[" + myAgent.getLocalName() + "] message reçu : " + msg.getContent());
            } else {
                block();
            }
        }
    }
}
