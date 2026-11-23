package smartparking.agents;

import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import smartparking.behaviours.EventReporter;
import smartparking.behaviours.ZoneCnpResponder;
import smartparking.models.ParkingConfig;
import smartparking.models.Slot;
import smartparking.models.SlotState;
import smartparking.services.PricingPolicy;
import smartparking.services.SimClock;
import smartparking.services.ZoneState;
import smartparking.utils.Config;
import smartparking.utils.EventTypes;
import smartparking.utils.Ontology;

import java.io.IOException;
import java.util.Map;

/**
 * Agent gérant une zone du parking (Standard, Électrique, PMR ou Premium).
 * Argument de démarrage : l'identifiant de la zone, tel qu'écrit dans config/parking.json.
 *
 * Rôle : s'enregistrer au DF, répondre aux appels d'offres (ZoneCnpResponder)
 * et faire le ménage régulièrement (réservations expirées, fins de stationnement).
 */
public class ZoneAgent extends Agent {

    private static final long MAINTENANCE_PERIOD_MS = 250;

    private ZoneState zone;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args == null || args.length < 1) {
            System.err.println("[" + getLocalName() + "] erreur : identifiant de zone manquant");
            doDelete();
            return;
        }
        String zoneId = args[0].toString();

        long holdTtlMs;
        long heartbeatMs;
        try {
            ParkingConfig config = Config.load();
            ParkingConfig.ZoneConfig zoneConfig = config.zones().stream()
                    .filter(z -> z.zoneId().equals(zoneId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("zone inconnue : " + zoneId));
            zone = new ZoneState(zoneConfig, new PricingPolicy(config.pricing()));
            holdTtlMs = config.holdTtlMs();
            heartbeatMs = config.heartbeatMs();
        } catch (IOException | RuntimeException e) {
            System.err.println("[" + getLocalName() + "] configuration invalide : " + e.getMessage());
            doDelete();
            return;
        }

        registerInDf();

        addBehaviour(new ZoneCnpResponder(this, zone, holdTtlMs, this::sendHeartbeat));
        addBehaviour(new TickerBehaviour(this, heartbeatMs) {
            @Override
            protected void onTick() {
                sendHeartbeat();
            }
        });
        addBehaviour(new TickerBehaviour(this, MAINTENANCE_PERIOD_MS) {
            @Override
            protected void onTick() {
                maintenance();
            }
        });

        System.out.println("[" + getLocalName() + "] prête : " + zone.getSlots().size()
                + " places, type " + zone.getZoneType() + ", enregistrée au DF");
    }

    /** Libère les places dont le stationnement est fini et les réservations expirées. */
    private void maintenance() {
        boolean changed = false;
        for (Slot slot : zone.releaseFinished(SimClock.get().nowSimMin())) {
            System.out.println("[" + getLocalName() + "] fin de stationnement : place "
                    + slot.getSlotId() + " libérée");
            changed = true;
        }
        for (String requestId : zone.expireHolds(System.currentTimeMillis())) {
            System.out.println("[" + getLocalName() + "] réservation expirée pour "
                    + requestId + " : place libérée");
            changed = true;
        }
        if (changed) {
            sendHeartbeat();
        }
    }

    /**
     * Signe de vie envoyé au Monitor : périodique (période heartbeatMs) et après chaque changement d'état,
     * avec le nombre de places utilisées (RESERVED ou OCCUPIED) et utilisables (hors OUT_OF_SERVICE).
     */
    private void sendHeartbeat() {
        int used = 0;
        int usable = 0;
        for (Slot slot : zone.getSlots()) {
            if (slot.getState() == SlotState.OUT_OF_SERVICE) {
                continue;
            }
            usable++;
            if (slot.getState() == SlotState.RESERVED || slot.getState() == SlotState.OCCUPIED) {
                used++;
            }
        }
        EventReporter.report(this, EventTypes.HEARTBEAT, null, zone.getZoneId(),
                Map.of("used", used, "usable", usable));
    }

    private void registerInDf() {
        try {
            DFAgentDescription description = new DFAgentDescription();
            description.setName(getAID());
            ServiceDescription service = new ServiceDescription();
            service.setType(Ontology.ZONE_SERVICE_TYPE);
            service.setName(zone.getZoneId());
            service.addProperties(new Property(Ontology.ZONE_TYPE_PROPERTY, zone.getZoneType().name()));
            description.addServices(service);
            DFService.register(this, description);
        } catch (FIPAException e) {
            System.err.println("[" + getLocalName() + "] échec d'enregistrement au DF : " + e.getMessage());
        }
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException e) {
            // l'agent s'arrête : rien d'autre à faire
        }
        System.out.println("[" + getLocalName() + "] arrêtée");
    }
}