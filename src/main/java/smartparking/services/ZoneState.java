package smartparking.services;

import smartparking.models.ParkingConfig;
import smartparking.models.ParkingRequest;
import smartparking.models.Proposal;
import smartparking.models.Refusal;
import smartparking.models.RefusalReason;
import smartparking.models.Slot;
import smartparking.models.SlotState;
import smartparking.models.ZoneType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * État et règles d'une zone : places, réservations provisoires (holds), confirmation.
 *
 * Règle centrale : une place proposée est immédiatement mise de côté (RESERVED) pour
 * la demande concernée. Elle ne peut donc pas être proposée à une autre demande.
 *
 * Classe en Java pur, non thread-safe : elle est utilisée par un seul agent JADE,
 * dont les comportements s'exécutent l'un après l'autre.
 */
public class ZoneState {

    /** Résultat d'un appel d'offres : soit une proposition, soit un refus motivé. */
    public record CfpOutcome(Proposal proposal, Refusal refusal) {
        public boolean isProposal() {
            return proposal != null;
        }
    }

    /** Résultat d'une confirmation réussie. */
    public record Confirmation(String slotId, double price, double startSimMin, double endSimMin) { }

    private record Hold(Slot slot, double unitPrice, double totalPrice, double durationHours,
                        double occupancyRate) { }

    private final String zoneId;
    private final ZoneType zoneType;
    private final List<Slot> slots;
    private final PricingPolicy pricing;
    private final Map<String, Hold> holds = new HashMap<>();

    /** Construit la zone et ses places à partir de la configuration. */
    public ZoneState(ParkingConfig.ZoneConfig config, PricingPolicy pricing) {
        this(config.zoneId(), config.type(), buildSlots(config), pricing);
    }

    public ZoneState(String zoneId, ZoneType zoneType, List<Slot> slots, PricingPolicy pricing) {
        this.zoneId = zoneId;
        this.zoneType = zoneType;
        this.slots = new ArrayList<>(slots);
        this.pricing = pricing;
    }

    /** Crée les places : identifiants ZoneX-01, ZoneX-02... et distances réparties de min à max. */
    private static List<Slot> buildSlots(ParkingConfig.ZoneConfig c) {
        List<Slot> list = new ArrayList<>();
        for (int i = 0; i < c.slotCount(); i++) {
            double distance = c.slotCount() == 1
                    ? c.minDistance()
                    : c.minDistance() + i * (c.maxDistance() - c.minDistance()) / (c.slotCount() - 1);
            list.add(new Slot(String.format("%s-%02d", c.zoneId(), i + 1), c.zoneId(), c.type(),
                    c.basePricePerHour(), distance,
                    c.type() == ZoneType.ELECTRIC, c.type() == ZoneType.PMR));
        }
        return list;
    }

    public String getZoneId() {
        return zoneId;
    }

    public ZoneType getZoneType() {
        return zoneType;
    }

    public List<Slot> getSlots() {
        return Collections.unmodifiableList(slots);
    }

    public int holdCount() {
        return holds.size();
    }

    public double occupancyRate() {
        return pricing.occupancyRate(slots);
    }

    /**
     * Compatibilité de type entre une place et une demande (contrainte obligatoire).
     * Les places spécialisées (borne, PMR) sont réservées à ceux qui en ont besoin.
     */
    public static boolean isCompatible(Slot slot, ParkingRequest request) {
        if (request.requiresCharging() && !slot.hasCharger()) {
            return false;
        }
        if (request.requiresPmr() && !slot.isPmr()) {
            return false;
        }
        if (!request.requiresCharging() && slot.hasCharger()) {
            return false;
        }
        if (!request.requiresPmr() && slot.isPmr()) {
            return false;
        }
        return true;
    }

    /**
     * Traite un appel d'offres (CFP) : propose et met de côté la meilleure place, ou refuse.
     * La meilleure place est la moins chère, puis la plus proche de la sortie, puis par identifiant.
     * Un deuxième CFP pour la même demande renvoie la même proposition (pas de double réservation).
     */
    public CfpOutcome handleCfp(ParkingRequest request, long nowRealMs, long holdTtlMs) {
        expireHolds(nowRealMs);

        Hold existing = holds.get(request.requestId());
        if (existing != null) {
            return new CfpOutcome(toProposal(request, existing), null);
        }

        if (request.duration() <= 0) {
            return refusal(request, RefusalReason.INCOMPATIBLE);
        }

        boolean anyCompatible = false;
        List<Slot> freeCompatible = new ArrayList<>();
        for (Slot slot : slots) {
            if (isCompatible(slot, request)) {
                anyCompatible = true;
                if (slot.isFree()) {
                    freeCompatible.add(slot);
                }
            }
        }
        if (!anyCompatible) {
            return refusal(request, RefusalReason.INCOMPATIBLE);
        }
        if (freeCompatible.isEmpty()) {
            return refusal(request, RefusalReason.ZONE_FULL);
        }

        double rate = occupancyRate();
        Slot best = null;
        double bestTotal = 0;
        for (Slot slot : freeCompatible) {
            double total = pricing.totalPrice(slot.getBasePricePerHour(), rate, request.duration());
            if (total > request.maxBudget()) {
                continue;
            }
            if (best == null || isBetter(slot, total, best, bestTotal)) {
                best = slot;
                bestTotal = total;
            }
        }
        if (best == null) {
            return refusal(request, RefusalReason.BUDGET_EXCEEDED);
        }

        best.setState(SlotState.RESERVED);
        best.setHeldBy(request.requestId());
        best.setHoldExpiresAtRealMs(nowRealMs + holdTtlMs);

        Hold hold = new Hold(best, pricing.unitPrice(best.getBasePricePerHour(), rate), bestTotal,
                request.duration(), rate);
        holds.put(request.requestId(), hold);
        return new CfpOutcome(toProposal(request, hold), null);
    }

    /**
     * Confirme une réservation (ACCEPT_PROPOSAL) : la place devient OCCUPIED.
     * Retourne vide si la réservation n'existe plus (expirée ou jamais posée) : c'est un conflit.
     */
    public Optional<Confirmation> confirm(String requestId, long nowRealMs, double nowSimMin) {
        expireHolds(nowRealMs);

        Hold hold = holds.remove(requestId);
        if (hold == null) {
            return Optional.empty();
        }
        Slot slot = hold.slot();
        if (slot.getState() != SlotState.RESERVED || !requestId.equals(slot.getHeldBy())) {
            return Optional.empty();
        }
        double end = nowSimMin + hold.durationHours() * 60.0;
        slot.setState(SlotState.OCCUPIED);
        slot.setOccupiedUntilSimMin(end);
        slot.setHoldExpiresAtRealMs(0);
        return Optional.of(new Confirmation(slot.getSlotId(), hold.totalPrice(), nowSimMin, end));
    }

    /** Libère la réservation d'une demande (REJECT_PROPOSAL). Retourne false si elle n'existe pas. */
    public boolean release(String requestId) {
        Hold hold = holds.remove(requestId);
        if (hold == null) {
            return false;
        }
        freeSlot(hold.slot());
        return true;
    }

    /** Libère les réservations dont le délai est dépassé ; retourne les demandes concernées. */
    public List<String> expireHolds(long nowRealMs) {
        List<String> expired = new ArrayList<>();
        Iterator<Map.Entry<String, Hold>> iterator = holds.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Hold> entry = iterator.next();
            Slot slot = entry.getValue().slot();
            if (slot.getHoldExpiresAtRealMs() <= nowRealMs) {
                freeSlot(slot);
                expired.add(entry.getKey());
                iterator.remove();
            }
        }
        Collections.sort(expired);
        return expired;
    }

    /** Libère les places dont la durée de stationnement est terminée (temps simulé). */
    public List<Slot> releaseFinished(double nowSimMin) {
        List<Slot> released = new ArrayList<>();
        for (Slot slot : slots) {
            if (slot.getState() == SlotState.OCCUPIED && slot.getOccupiedUntilSimMin() <= nowSimMin) {
                freeSlot(slot);
                released.add(slot);
            }
        }
        return released;
    }

    private static boolean isBetter(Slot candidate, double candidateTotal, Slot current, double currentTotal) {
        return Comparator.<Double>naturalOrder().compare(candidateTotal, currentTotal) < 0
                || (candidateTotal == currentTotal
                && (candidate.getDistanceToExit() < current.getDistanceToExit()
                || (candidate.getDistanceToExit() == current.getDistanceToExit()
                && candidate.getSlotId().compareTo(current.getSlotId()) < 0)));
    }

    private Proposal toProposal(ParkingRequest request, Hold hold) {
        Slot slot = hold.slot();
        return new Proposal(request.requestId(), zoneId, zoneType, slot.getSlotId(),
                hold.unitPrice(), hold.totalPrice(), slot.getDistanceToExit(),
                hold.occupancyRate(), slot.getHoldExpiresAtRealMs());
    }

    private CfpOutcome refusal(ParkingRequest request, RefusalReason reason) {
        return new CfpOutcome(null, new Refusal(request.requestId(), zoneId, reason));
    }

    private static void freeSlot(Slot slot) {
        slot.setState(SlotState.FREE);
        slot.setHeldBy(null);
        slot.setHoldExpiresAtRealMs(0);
        slot.setOccupiedUntilSimMin(0);
    }
}