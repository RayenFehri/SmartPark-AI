package smartparking.agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.proto.ContractNetInitiator;
import smartparking.models.ParkingRequest;
import smartparking.models.PreferredLocation;
import smartparking.models.Priority;
import smartparking.models.Proposal;
import smartparking.models.VehicleType;
import smartparking.utils.JsonUtil;
import smartparking.utils.Ontology;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Vector;

/**
 * Agent de test TEMPORAIRE : joue le rôle du Manager pour vérifier les ZoneAgents.
 * Envoie 5 appels d'offres en parallèle, choisit l'offre la moins chère de chacun,
 * et affiche tout ce qui se passe. Sera supprimé à l'étape 3.
 */
public class TestInitiatorAgent extends Agent {

    private static final long REPLY_TIMEOUT_MS = 2000;

    @Override
    protected void setup() {
        System.out.println("[tester] prêt, les négociations démarrent dans 2 secondes");
        addBehaviour(new WakerBehaviour(this, 2000) {
            @Override
            protected void onWake() {
                launchNegotiations();
            }
        });
    }

    private void launchNegotiations() {
        List<AID> zones = findZones();
        if (zones.isEmpty()) {
            System.out.println("[tester] aucune zone trouvée dans le DF");
            return;
        }
        System.out.println("[tester] " + zones.size() + " zones trouvées, envoi de 5 appels d'offres");

        List<ParkingRequest> requests = List.of(
                request("T-STD", 2.0, 20.0, VehicleType.STANDARD, false, false, Priority.NORMAL),
                request("T-ELEC", 3.0, 12.0, VehicleType.ELECTRIC, true, false, Priority.NORMAL),
                request("T-PMR", 1.0, 10.0, VehicleType.PMR, false, true, Priority.PMR),
                request("T-POOR", 3.0, 8.0, VehicleType.ELECTRIC, true, false, Priority.NORMAL),
                request("T-GHOST", 1.0, 20.0, VehicleType.STANDARD, false, false, Priority.NORMAL));

        for (ParkingRequest request : requests) {
            boolean ignoreProposals = request.requestId().equals("T-GHOST");
            addBehaviour(new TestNegotiation(this, buildCfp(request, zones), ignoreProposals));
        }
    }

    private List<AID> findZones() {
        List<AID> zones = new ArrayList<>();
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription service = new ServiceDescription();
            service.setType(Ontology.ZONE_SERVICE_TYPE);
            template.addServices(service);
            for (DFAgentDescription found : DFService.search(this, template)) {
                zones.add(found.getName());
            }
        } catch (FIPAException e) {
            System.err.println("[tester] erreur DF : " + e.getMessage());
        }
        return zones;
    }

    private static ParkingRequest request(String id, double duration, double budget, VehicleType type,
                                          boolean charging, boolean pmr, Priority priority) {
        return new ParkingRequest(id, "V-" + id, duration, budget, type, charging, pmr, priority,
                PreferredLocation.ANY, 0L);
    }

    private static ACLMessage buildCfp(ParkingRequest request, List<AID> zones) {
        ACLMessage cfp = new ACLMessage(ACLMessage.CFP);
        cfp.setProtocol(FIPANames.InteractionProtocol.FIPA_CONTRACT_NET);
        cfp.setOntology(Ontology.NAME);
        cfp.setLanguage(Ontology.LANGUAGE);
        cfp.setConversationId(request.requestId());
        cfp.setContent(JsonUtil.toJson(request));
        cfp.setReplyByDate(new Date(System.currentTimeMillis() + REPLY_TIMEOUT_MS));
        for (AID zone : zones) {
            cfp.addReceiver(zone);
        }
        return cfp;
    }

    /** Une négociation Contract Net côté initiateur (une instance par demande). */
    private static class TestNegotiation extends ContractNetInitiator {

        private final boolean ignoreProposals;

        TestNegotiation(Agent agent, ACLMessage cfp, boolean ignoreProposals) {
            super(agent, cfp);
            this.ignoreProposals = ignoreProposals;
        }

        @Override
        @SuppressWarnings({"rawtypes", "unchecked"})
        protected void handleAllResponses(Vector responses, Vector acceptances) {
            String id = "?";
            ACLMessage best = null;
            double bestPrice = Double.MAX_VALUE;

            for (Object o : responses) {
                ACLMessage message = (ACLMessage) o;
                id = message.getConversationId();
                if (message.getPerformative() == ACLMessage.PROPOSE) {
                    Proposal proposal = JsonUtil.fromJson(message.getContent(), Proposal.class);
                    System.out.println("[tester] " + id + " : " + message.getSender().getLocalName()
                            + " propose " + proposal.slotId() + " à " + proposal.totalPrice());
                    if (proposal.totalPrice() < bestPrice) {
                        bestPrice = proposal.totalPrice();
                        best = message;
                    }
                } else {
                    System.out.println("[tester] " + id + " : " + message.getSender().getLocalName()
                            + " refuse " + message.getContent());
                }
            }

            if (ignoreProposals) {
                System.out.println("[tester] " + id + " : je ne réponds à aucune proposition "
                        + "(test de l'expiration des réservations)");
                return;
            }

            for (Object o : responses) {
                ACLMessage message = (ACLMessage) o;
                if (message.getPerformative() != ACLMessage.PROPOSE) {
                    continue;
                }
                ACLMessage answer = message.createReply();
                if (message == best) {
                    answer.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                    System.out.println("[tester] " + id + " : j'ACCEPTE " + message.getSender().getLocalName());
                } else {
                    answer.setPerformative(ACLMessage.REJECT_PROPOSAL);
                    System.out.println("[tester] " + id + " : je REJETTE " + message.getSender().getLocalName());
                }
                acceptances.add(answer);
            }
            if (best == null) {
                System.out.println("[tester] " + id + " : aucune offre, la demande est refusée");
            }
        }

        @Override
        protected void handleInform(ACLMessage inform) {
            System.out.println("[tester] " + inform.getConversationId() + " : confirmation de "
                    + inform.getSender().getLocalName() + " " + inform.getContent());
        }

        @Override
        protected void handleFailure(ACLMessage failure) {
            System.out.println("[tester] " + failure.getConversationId() + " : ÉCHEC de "
                    + failure.getSender().getLocalName() + " " + failure.getContent());
        }
    }
}