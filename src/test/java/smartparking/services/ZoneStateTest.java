package smartparking.services;

import org.junit.jupiter.api.Test;
import smartparking.models.ParkingConfig;
import smartparking.models.ParkingRequest;
import smartparking.models.PreferredLocation;
import smartparking.models.Priority;
import smartparking.models.Proposal;
import smartparking.models.RefusalReason;
import smartparking.models.SlotState;
import smartparking.models.VehicleType;
import smartparking.models.ZoneType;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneStateTest {

    private static final PricingPolicy PRICING =
            new PricingPolicy(new double[] {0.4, 0.7, 0.9}, new double[] {0.0, 0.1, 0.2, 0.3});

    private static ZoneState zone(String id, ZoneType type, int slots) {
        return new ZoneState(new ParkingConfig.ZoneConfig(id, type, slots, 2.0, 60.0, 150.0), PRICING);
    }

    private static ZoneState standardZone(int slots) {
        return zone("ZoneStandard", ZoneType.STANDARD, slots);
    }

    private static ParkingRequest standardRequest(String id, double duration, double budget) {
        return new ParkingRequest(id, "V-" + id, duration, budget, VehicleType.STANDARD,
                false, false, Priority.NORMAL, PreferredLocation.ANY, 0L);
    }

    private static long count(ZoneState zone, SlotState state) {
        return zone.getSlots().stream().filter(s -> s.getState() == state).count();
    }

    @Test
    void compatibleRequestGetsProposalAndSlotIsHeld() {
        ZoneState zone = standardZone(4);
        ZoneState.CfpOutcome outcome = zone.handleCfp(standardRequest("R1", 2.0, 20.0), 1000L, 5000L);

        assertTrue(outcome.isProposal());
        Proposal proposal = outcome.proposal();
        assertEquals("ZoneStandard-01", proposal.slotId());          // la plus proche de la sortie
        assertEquals(4.0, proposal.totalPrice(), 1e-9);              // 2 DT/h x 2 h, occupation 0 %
        assertEquals(6000L, proposal.holdExpiresAtRealMs());
        assertEquals(1L, count(zone, SlotState.RESERVED));
    }

    @Test
    void requestNeedingChargingIsRefusedByStandardZone() {
        ParkingRequest electric = new ParkingRequest("R1", "V1", 2.0, 20.0, VehicleType.ELECTRIC,
                true, false, Priority.NORMAL, PreferredLocation.ANY, 0L);
        ZoneState.CfpOutcome outcome = standardZone(4).handleCfp(electric, 1000L, 5000L);

        assertFalse(outcome.isProposal());
        assertEquals(RefusalReason.INCOMPATIBLE, outcome.refusal().reason());
    }

    @Test
    void specializedZonesOnlyServeTheirOwnNeeds() {
        ZoneState electricZone = zone("ZoneElectric", ZoneType.ELECTRIC, 2);
        ZoneState pmrZone = zone("ZonePMR", ZoneType.PMR, 2);

        ParkingRequest electric = new ParkingRequest("R1", "V1", 1.0, 20.0, VehicleType.ELECTRIC,
                true, false, Priority.NORMAL, PreferredLocation.ANY, 0L);
        ParkingRequest pmr = new ParkingRequest("R2", "V2", 1.0, 20.0, VehicleType.PMR,
                false, true, Priority.PMR, PreferredLocation.ANY, 0L);
        ParkingRequest standard = standardRequest("R3", 1.0, 20.0);

        assertTrue(electricZone.handleCfp(electric, 0L, 5000L).isProposal());
        assertEquals(RefusalReason.INCOMPATIBLE, electricZone.handleCfp(standard, 0L, 5000L).refusal().reason());
        assertTrue(pmrZone.handleCfp(pmr, 0L, 5000L).isProposal());
        assertEquals(RefusalReason.INCOMPATIBLE, pmrZone.handleCfp(standard, 0L, 5000L).refusal().reason());
    }

    @Test
    void budgetTooLowIsRefusedAndNothingIsHeld() {
        ZoneState zone = standardZone(4);
        ZoneState.CfpOutcome outcome = zone.handleCfp(standardRequest("R1", 2.0, 1.0), 1000L, 5000L);

        assertFalse(outcome.isProposal());
        assertEquals(RefusalReason.BUDGET_EXCEEDED, outcome.refusal().reason());
        assertEquals(0L, count(zone, SlotState.RESERVED));
    }

    @Test
    void fullZoneRefusesFurtherRequests() {
        ZoneState zone = standardZone(4);
        for (int i = 1; i <= 4; i++) {
            assertTrue(zone.handleCfp(standardRequest("R" + i, 1.0, 20.0), 1000L, 5000L).isProposal());
        }
        ZoneState.CfpOutcome fifth = zone.handleCfp(standardRequest("R5", 1.0, 20.0), 1000L, 5000L);

        assertFalse(fifth.isProposal());
        assertEquals(RefusalReason.ZONE_FULL, fifth.refusal().reason());
    }

    @Test
    void rejectFreesTheSlotImmediately() {
        ZoneState zone = standardZone(4);
        zone.handleCfp(standardRequest("R1", 2.0, 20.0), 1000L, 5000L);

        assertTrue(zone.release("R1"));
        assertEquals(0L, count(zone, SlotState.RESERVED));
        assertEquals(4L, count(zone, SlotState.FREE));
        assertFalse(zone.release("R1"));   // déjà libérée
    }

    @Test
    void holdExpiresExactlyAtItsDeadline() {
        ZoneState zone = standardZone(4);
        zone.handleCfp(standardRequest("R1", 2.0, 20.0), 1000L, 5000L);   // expire à 6000

        assertTrue(zone.expireHolds(5999L).isEmpty());
        assertEquals(1L, count(zone, SlotState.RESERVED));
        assertEquals(List.of("R1"), zone.expireHolds(6000L));
        assertEquals(0L, count(zone, SlotState.RESERVED));
    }

    @Test
    void confirmAfterExpiryFailsAsAConflict() {
        ZoneState zone = standardZone(4);
        zone.handleCfp(standardRequest("R1", 2.0, 20.0), 1000L, 5000L);

        Optional<ZoneState.Confirmation> result = zone.confirm("R1", 7000L, 10.0);

        assertTrue(result.isEmpty());
        assertEquals(0L, count(zone, SlotState.OCCUPIED));
    }

    @Test
    void confirmOccupiesTheSlotUntilTheEndOfTheStay() {
        ZoneState zone = standardZone(4);
        zone.handleCfp(standardRequest("R1", 2.0, 20.0), 1000L, 5000L);

        Optional<ZoneState.Confirmation> result = zone.confirm("R1", 2000L, 10.0);

        assertTrue(result.isPresent());
        assertEquals(4.0, result.get().price(), 1e-9);
        assertEquals(130.0, result.get().endSimMin(), 1e-9);          // 10 + 2 h x 60 min
        assertEquals(1L, count(zone, SlotState.OCCUPIED));

        assertTrue(zone.releaseFinished(129.9).isEmpty());
        assertEquals(1, zone.releaseFinished(130.0).size());
        assertEquals(4L, count(zone, SlotState.FREE));
    }

    @Test
    void aSlotIsNeverProposedToTwoRequests() {
        ZoneState zone = standardZone(4);
        Set<String> proposedSlots = new HashSet<>();
        int proposals = 0;
        for (int i = 1; i <= 10; i++) {
            ZoneState.CfpOutcome outcome = zone.handleCfp(standardRequest("R" + i, 1.0, 50.0), 1000L, 5000L);
            if (outcome.isProposal()) {
                proposals++;
                proposedSlots.add(outcome.proposal().slotId());
            }
        }
        assertEquals(4, proposals);
        assertEquals(4, proposedSlots.size());   // aucune place proposée deux fois
    }

    @Test
    void duplicateCfpForTheSameRequestHoldsOnlyOneSlot() {
        ZoneState zone = standardZone(4);
        ParkingRequest request = standardRequest("R1", 2.0, 20.0);

        Proposal first = zone.handleCfp(request, 1000L, 5000L).proposal();
        Proposal second = zone.handleCfp(request, 1500L, 5000L).proposal();

        assertEquals(first.slotId(), second.slotId());
        assertEquals(1L, count(zone, SlotState.RESERVED));
        assertEquals(1, zone.holdCount());
    }

    @Test
    void priceRisesWhenTheZoneFillsUp() {
        ZoneState zone = standardZone(4);
        zone.handleCfp(standardRequest("R1", 1.0, 50.0), 1000L, 5000L);
        zone.handleCfp(standardRequest("R2", 1.0, 50.0), 1000L, 5000L);

        // 2 places sur 4 réservées = 50 % : +10 % -> 2 x 1,1 x 1 h = 2,2
        Proposal third = zone.handleCfp(standardRequest("R3", 1.0, 50.0), 1000L, 5000L).proposal();
        assertEquals(2.2, third.totalPrice(), 1e-9);
        assertEquals(0.5, third.occupancyRate(), 1e-9);
    }
}