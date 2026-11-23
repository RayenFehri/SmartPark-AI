package smartparking.services;

import org.junit.jupiter.api.Test;
import smartparking.models.Alternative;
import smartparking.models.ParkingConfig;
import smartparking.models.ParkingRequest;
import smartparking.models.PreferredLocation;
import smartparking.models.Priority;
import smartparking.models.RefusalReason;
import smartparking.models.VehicleType;
import smartparking.models.ZoneType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlternativeAdvisorTest {

    private static final List<ParkingConfig.ZoneConfig> ZONES = List.of(
            new ParkingConfig.ZoneConfig("ZoneStandard", ZoneType.STANDARD, 20, 2.0, 60.0, 150.0),
            new ParkingConfig.ZoneConfig("ZoneElectric", ZoneType.ELECTRIC, 8, 3.0, 40.0, 100.0),
            new ParkingConfig.ZoneConfig("ZonePMR", ZoneType.PMR, 4, 2.0, 10.0, 30.0),
            new ParkingConfig.ZoneConfig("ZonePremium", ZoneType.PREMIUM, 8, 4.0, 5.0, 25.0));

    private static final AlternativeAdvisor ADVISOR = new AlternativeAdvisor(ZONES, 0.3);

    private static ParkingRequest standard(double duration, double budget) {
        return new ParkingRequest("R1", "V1", duration, budget, VehicleType.STANDARD,
                false, false, Priority.NORMAL, PreferredLocation.ANY, 0L);
    }

    private static ParkingRequest electric(double duration, double budget) {
        return new ParkingRequest("R2", "V2", duration, budget, VehicleType.ELECTRIC,
                true, false, Priority.NORMAL, PreferredLocation.ANY, 0L);
    }

    @Test
    void compatibilityMatchesTheStrictZoneRules() {
        assertTrue(AlternativeAdvisor.isCompatible(standard(1, 10), ZoneType.STANDARD));
        assertTrue(AlternativeAdvisor.isCompatible(standard(1, 10), ZoneType.PREMIUM));
        assertFalse(AlternativeAdvisor.isCompatible(standard(1, 10), ZoneType.ELECTRIC));
        assertTrue(AlternativeAdvisor.isCompatible(electric(1, 10), ZoneType.ELECTRIC));
        assertFalse(AlternativeAdvisor.isCompatible(electric(1, 10), ZoneType.STANDARD));
    }

    @Test
    void fullZoneGivesTheSoonestReleaseAmongCompatibleZones() {
        Map<String, List<Double>> ends = Map.of(
                "ZoneElectric", List.of(150.0, 130.0, 90.0),
                "ZoneStandard", List.of(105.0));   // zone non compatible : ignorée

        List<Alternative> result = ADVISOR.advise(electric(3, 12), RefusalReason.ZONE_FULL, ends, 100.0);

        assertEquals(1, result.size());
        assertEquals(Alternative.WAIT_ESTIMATE, result.get(0).kind());
        assertEquals("ZoneElectric", result.get(0).zoneId());
        assertEquals(30.0, result.get(0).value(), 1e-9);   // 130 - 100 ; 90 est déjà passé
    }

    @Test
    void fullZoneWithoutKnownReleaseGivesNoAlternative() {
        assertTrue(ADVISOR.advise(electric(3, 12), RefusalReason.ZONE_FULL, Map.of(), 0.0).isEmpty());
    }

    @Test
    void tightBudgetSuggestsAShorterStayInTheCheapestCompatibleZone() {
        // budget 7 DT, Standard 2 DT/h, majoration maximale 30 % : 7 / 2,6 = 2,69 h -> 2,5 h
        List<Alternative> result = ADVISOR.advise(standard(4.0, 7.0), RefusalReason.BUDGET_EXCEEDED, Map.of(), 0.0);

        assertEquals(1, result.size());
        assertEquals(Alternative.REDUCED_DURATION, result.get(0).kind());
        assertEquals("ZoneStandard", result.get(0).zoneId());
        assertEquals(2.5, result.get(0).value(), 1e-9);
    }

    @Test
    void noShorterStayIsOfferedWhenEvenHalfAnHourIsTooExpensive() {
        assertTrue(ADVISOR.advise(standard(4.0, 1.0), RefusalReason.BUDGET_EXCEEDED, Map.of(), 0.0).isEmpty());
    }

    @Test
    void otherReasonsGiveNoAlternative() {
        assertTrue(ADVISOR.advise(standard(2.0, 20.0), RefusalReason.INCOMPATIBLE, Map.of(), 0.0).isEmpty());
    }
}