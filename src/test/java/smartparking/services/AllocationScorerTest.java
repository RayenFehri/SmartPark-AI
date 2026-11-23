package smartparking.services;

import org.junit.jupiter.api.Test;
import smartparking.models.ParkingConfig;
import smartparking.models.ParkingRequest;
import smartparking.models.PreferredLocation;
import smartparking.models.Priority;
import smartparking.models.Proposal;
import smartparking.models.Refusal;
import smartparking.models.RefusalReason;
import smartparking.models.VehicleType;
import smartparking.models.ZoneType;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllocationScorerTest {

    private static final Map<String, ParkingConfig.Weights> WEIGHTS = Map.of(
            "default", new ParkingConfig.Weights(0.4, 0.4, 0.2),
            "nearExit", new ParkingConfig.Weights(0.5, 0.3, 0.2),
            "tightBudget", new ParkingConfig.Weights(0.3, 0.5, 0.2));

    // distance maximale du parking : 150 ; tarif de base le plus bas : 2 DT/h
    private static final AllocationScorer SCORER = new AllocationScorer(WEIGHTS, 150.0, 2.0);

    private static ParkingRequest standard(double duration, double budget, PreferredLocation location) {
        return new ParkingRequest("R1", "V1", duration, budget, VehicleType.STANDARD,
                false, false, Priority.NORMAL, location, 0L);
    }

    private static Proposal offer(String zone, ZoneType type, String slot, double total, double distance) {
        return new Proposal("R1", zone, type, slot, total, total, distance, 0.0, 6000L);
    }

    private static final Proposal STANDARD_OFFER = offer("ZoneStandard", ZoneType.STANDARD, "ZoneStandard-01", 4.0, 60.0);
    private static final Proposal PREMIUM_OFFER = offer("ZonePremium", ZoneType.PREMIUM, "ZonePremium-01", 8.0, 15.0);

    @Test
    void scoreFollowsTheFormulaWithDefaultWeights() {
        ParkingRequest request = standard(2.0, 20.0, PreferredLocation.ANY);
        // Dn = 60/150 = 0,4 ; Pn = 4/20 = 0,2 ; Wn = 0 -> 0,4 x 0,4 + 0,4 x 0,2 = 0,24
        assertEquals(0.24, SCORER.score(request, STANDARD_OFFER), 1e-9);
        // Premium non demandée : Dn = 0,1 ; Pn = 0,4 ; Wn = 0,5 -> 0,04 + 0,16 + 0,10 = 0,30
        assertEquals(0.30, SCORER.score(request, PREMIUM_OFFER), 1e-9);
    }

    @Test
    void weightProfileDependsOnTheRequest() {
        assertEquals("default", SCORER.profileFor(standard(2.0, 20.0, PreferredLocation.ANY)));
        assertEquals("nearExit", SCORER.profileFor(standard(2.0, 20.0, PreferredLocation.NEAR_EXIT)));
        // budget 5 < 1,3 x 2 DT/h x 2 h = 5,2 : budget serré, même si la proximité est demandée
        assertEquals("tightBudget", SCORER.profileFor(standard(2.0, 5.0, PreferredLocation.NEAR_EXIT)));
        // exactement 5,2 : pas serré (la borne est exclue)
        assertEquals("default", SCORER.profileFor(standard(2.0, 5.2, PreferredLocation.ANY)));
    }

    @Test
    void nearExitPreferenceMakesPremiumWin() {
        ParkingRequest request = standard(2.0, 20.0, PreferredLocation.NEAR_EXIT);
        // Standard : 0,5 x 0,4 + 0,3 x 0,2 = 0,26 ; Premium demandée : 0,5 x 0,1 + 0,3 x 0,4 = 0,17
        AllocationScorer.Result result = SCORER.evaluate(request, List.of(STANDARD_OFFER, PREMIUM_OFFER),
                List.of(), Set.of());

        assertTrue(result.isAllocated());
        assertEquals("ZonePremium", result.best().zoneId());
        assertEquals(0.17, result.bestScore(), 1e-9);
    }

    @Test
    void withoutPreferenceTheCheaperStandardZoneWins() {
        ParkingRequest request = standard(2.0, 20.0, PreferredLocation.ANY);
        AllocationScorer.Result result = SCORER.evaluate(request, List.of(PREMIUM_OFFER, STANDARD_OFFER),
                List.of(), Set.of());

        assertEquals("ZoneStandard", result.best().zoneId());
        assertEquals("ZoneStandard", result.trace().selectedZoneId());
        assertEquals("SELECTED", result.trace().evaluations().get(0).status());
        assertEquals("NOT_SELECTED", result.trace().evaluations().get(1).status());
    }

    @Test
    void offerOverBudgetIsEliminatedWithAReason() {
        ParkingRequest request = standard(2.0, 6.0, PreferredLocation.ANY);
        AllocationScorer.Result result = SCORER.evaluate(request, List.of(STANDARD_OFFER, PREMIUM_OFFER),
                List.of(), Set.of());

        assertEquals("ZoneStandard", result.best().zoneId());
        var premium = result.trace().evaluations().stream()
                .filter(e -> e.zoneId().equals("ZonePremium")).findFirst().orElseThrow();
        assertEquals("ELIMINATED", premium.status());
        assertNull(premium.score());
        assertTrue(premium.reason().contains("budget"));
    }

    @Test
    void offerFromADownZoneIsEliminated() {
        ParkingRequest request = standard(2.0, 20.0, PreferredLocation.ANY);
        AllocationScorer.Result result = SCORER.evaluate(request, List.of(STANDARD_OFFER, PREMIUM_OFFER),
                List.of(), Set.of("ZoneStandard"));

        assertEquals("ZonePremium", result.best().zoneId());
    }

    @Test
    void chargingRequestRejectsNonElectricOffers() {
        ParkingRequest request = new ParkingRequest("R1", "V1", 2.0, 20.0, VehicleType.ELECTRIC,
                true, false, Priority.NORMAL, PreferredLocation.ANY, 0L);
        Proposal electric = offer("ZoneElectric", ZoneType.ELECTRIC, "ZoneElectric-01", 6.0, 40.0);

        AllocationScorer.Result result = SCORER.evaluate(request, List.of(STANDARD_OFFER, electric),
                List.of(), Set.of());

        assertEquals("ZoneElectric", result.best().zoneId());
        assertEquals(0.0, SCORER.wastePenalty(request, ZoneType.ELECTRIC), 1e-9);
    }

    @Test
    void unneededSpecializedZoneIsOnlyAFallback() {
        ParkingRequest request = standard(2.0, 20.0, PreferredLocation.ANY);
        Proposal electric = offer("ZoneElectric", ZoneType.ELECTRIC, "ZoneElectric-01", 6.0, 40.0);

        // une offre adaptée existe : la zone électrique est écartée même si elle est plus proche
        AllocationScorer.Result withAlternative = SCORER.evaluate(request, List.of(electric, STANDARD_OFFER),
                List.of(), Set.of());
        assertEquals("ZoneStandard", withAlternative.best().zoneId());
        assertTrue(withAlternative.trace().evaluations().stream()
                .anyMatch(e -> e.zoneId().equals("ZoneElectric") && e.status().equals("ELIMINATED")));

        // seule offre : repli exceptionnel, pénalisé (Wn = 1)
        // Dn = 40/150 ; Pn = 0,3 ; Wn = 1 -> 0,4 x 0,266667 + 0,4 x 0,3 + 0,2 x 1
        AllocationScorer.Result onlyFallback = SCORER.evaluate(request, List.of(electric), List.of(), Set.of());
        assertTrue(onlyFallback.isAllocated());
        assertEquals(0.4 * (40.0 / 150.0) + 0.12 + 0.2, onlyFallback.bestScore(), 1e-6);
    }

    @Test
    void tiesAreBrokenByZoneIdThenSlotId() {
        ParkingRequest request = standard(2.0, 20.0, PreferredLocation.ANY);
        Proposal b = offer("ZoneStandard", ZoneType.STANDARD, "ZoneStandard-02", 4.0, 60.0);
        Proposal a = offer("ZoneStandard", ZoneType.STANDARD, "ZoneStandard-01", 4.0, 60.0);

        AllocationScorer.Result result = SCORER.evaluate(request, List.of(b, a), List.of(), Set.of());

        assertEquals("ZoneStandard-01", result.best().slotId());
    }

    @Test
    void singleOfferWorksWithoutAnyRelativeNormalization() {
        ParkingRequest request = standard(2.0, 20.0, PreferredLocation.ANY);
        AllocationScorer.Result result = SCORER.evaluate(request, List.of(STANDARD_OFFER), List.of(), Set.of());

        assertTrue(result.isAllocated());
        assertEquals(0.24, result.bestScore(), 1e-9);
        assertFalse(Double.isNaN(result.bestScore()));
    }

    @Test
    void noValidOfferGivesNoAllocationButAFullTrace() {
        ParkingRequest request = standard(2.0, 20.0, PreferredLocation.ANY);
        List<Refusal> refusals = List.of(new Refusal("R1", "ZoneElectric", RefusalReason.INCOMPATIBLE));

        AllocationScorer.Result result = SCORER.evaluate(request, List.of(), refusals, Set.of());

        assertFalse(result.isAllocated());
        assertNull(result.trace().selectedZoneId());
        assertEquals(1, result.trace().evaluations().size());
        assertTrue(result.trace().evaluations().get(0).reason().contains("INCOMPATIBLE"));
    }

    @Test
    void offerBelongingToAnotherRequestIsIgnored() {
        ParkingRequest request = standard(2.0, 20.0, PreferredLocation.ANY);
        Proposal foreign = new Proposal("R2", "ZoneStandard", ZoneType.STANDARD, "ZoneStandard-03",
                4.0, 4.0, 60.0, 0.0, 6000L);

        AllocationScorer.Result result = SCORER.evaluate(request, List.of(foreign), List.of(), Set.of());

        assertFalse(result.isAllocated());
    }

    @Test
    void missingWeightProfileIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new AllocationScorer(Map.of("default", new ParkingConfig.Weights(0.4, 0.4, 0.2)), 150.0, 2.0));
    }

    // --- stratégie A : FIRST_AVAILABLE ---

    private static final AllocationScorer FIRST_AVAILABLE_SCORER = new AllocationScorer(WEIGHTS, 150.0, 2.0,
            List.of("ZoneStandard", "ZoneElectric", "ZonePMR", "ZonePremium"));

    @Test
    void firstAvailableFollowsZoneOrderWhileTheScoreWouldPickAnotherZone() {
        ParkingRequest request = standard(2.0, 20.0, PreferredLocation.NEAR_EXIT);
        List<Proposal> offers = List.of(PREMIUM_OFFER, STANDARD_OFFER);

        // stratégie B : Premium (proche de la sortie, demandée) ; stratégie A : première zone de la liste
        assertEquals("ZonePremium", SCORER.evaluate(request, offers, List.of(), Set.of()).best().zoneId());
        AllocationScorer.Result a = FIRST_AVAILABLE_SCORER.evaluateFirstAvailable(request, offers, List.of(), Set.of());
        assertEquals("ZoneStandard", a.best().zoneId());
        assertEquals("SELECTED", a.trace().evaluations().get(0).status());
        assertNull(a.trace().evaluations().get(0).score());
    }

    @Test
    void firstAvailableStillAppliesTheHardConstraints() {
        ParkingRequest request = standard(2.0, 6.0, PreferredLocation.ANY);
        Proposal tooExpensive = offer("ZoneStandard", ZoneType.STANDARD, "ZoneStandard-01", 7.0, 60.0);
        Proposal affordable = offer("ZonePremium", ZoneType.PREMIUM, "ZonePremium-01", 5.0, 15.0);

        AllocationScorer.Result a = FIRST_AVAILABLE_SCORER.evaluateFirstAvailable(request,
                List.of(tooExpensive, affordable), List.of(), Set.of());
        assertEquals("ZonePremium", a.best().zoneId());

        AllocationScorer.Result down = FIRST_AVAILABLE_SCORER.evaluateFirstAvailable(request,
                List.of(affordable), List.of(), Set.of("ZonePremium"));
        assertFalse(down.isAllocated());
    }

    @Test
    void firstAvailableWithoutOfferGivesNoAllocation() {
        ParkingRequest request = standard(2.0, 20.0, PreferredLocation.ANY);
        AllocationScorer.Result a = FIRST_AVAILABLE_SCORER.evaluateFirstAvailable(request, List.of(),
                List.of(new Refusal("R1", "ZoneStandard", RefusalReason.ZONE_FULL)), Set.of());

        assertFalse(a.isAllocated());
        assertEquals(1, a.trace().evaluations().size());
    }
}