package smartparking.utils;

import org.junit.jupiter.api.Test;
import smartparking.models.Allocation;
import smartparking.models.DecisionTrace;
import smartparking.models.Event;
import smartparking.models.OfferEvaluation;
import smartparking.models.ParkingRequest;
import smartparking.models.PreferredLocation;
import smartparking.models.Priority;
import smartparking.models.Proposal;
import smartparking.models.Refusal;
import smartparking.models.RefusalReason;
import smartparking.models.VehicleType;
import smartparking.models.ZoneType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonUtilTest {

    @Test
    void parkingRequestRoundTrip() {
        ParkingRequest request = new ParkingRequest("S1-R001", "Vehicle1", 3.0, 8.0, VehicleType.ELECTRIC,
                true, false, Priority.NORMAL, PreferredLocation.NEAR_EXIT, 42L);
        String json = JsonUtil.toJson(request);
        assertEquals(request, JsonUtil.fromJson(json, ParkingRequest.class));
    }

    @Test
    void llmContractJsonIsReadable() {
        // Exemple du cahier des charges : sortie attendue du LLM (sans identifiants)
        String json = "{\"duration\":3,\"maxBudget\":8,\"vehicleType\":\"ELECTRIC\","
                + "\"requiresCharging\":true,\"priority\":\"NORMAL\",\"preferredLocation\":\"NEAR_EXIT\"}";
        ParkingRequest request = JsonUtil.fromJson(json, ParkingRequest.class);
        assertEquals(3.0, request.duration());
        assertEquals(8.0, request.maxBudget());
        assertEquals(VehicleType.ELECTRIC, request.vehicleType());
        assertTrue(request.requiresCharging());
        assertFalse(request.requiresPmr());
        assertEquals(Priority.NORMAL, request.priority());
        assertEquals(PreferredLocation.NEAR_EXIT, request.preferredLocation());
        assertNull(request.requestId());
    }

    @Test
    void withIdsKeepsOtherFields() {
        ParkingRequest parsed = JsonUtil.fromJson(
                "{\"duration\":2,\"maxBudget\":5,\"vehicleType\":\"STANDARD\"}", ParkingRequest.class);
        ParkingRequest full = parsed.withIds("R1", "V1");
        assertEquals("R1", full.requestId());
        assertEquals("V1", full.vehicleId());
        assertEquals(2.0, full.duration());
        assertEquals(VehicleType.STANDARD, full.vehicleType());
    }

    @Test
    void proposalAndRefusalRoundTrip() {
        Proposal proposal = new Proposal("R1", "ZoneElectric", ZoneType.ELECTRIC, "ZoneElectric-03",
                3.3, 9.9, 55.0, 0.5, 123456789L);
        assertEquals(proposal, JsonUtil.fromJson(JsonUtil.toJson(proposal), Proposal.class));

        Refusal refusal = new Refusal("R1", "ZonePMR", RefusalReason.ZONE_FULL);
        assertEquals(refusal, JsonUtil.fromJson(JsonUtil.toJson(refusal), Refusal.class));
    }

    @Test
    void allocationAndEventRoundTrip() {
        Allocation allocation = new Allocation("R1", "V1", "ZonePremium", "ZonePremium-01", 12.5, 0.31, 10.0, 130.0);
        assertEquals(allocation, JsonUtil.fromJson(JsonUtil.toJson(allocation), Allocation.class));

        Event event = new Event(1000L, 12.5, "ALLOCATED", "ParkingManager", "R1", "ZonePremium", "score=0.31");
        assertEquals(event, JsonUtil.fromJson(JsonUtil.toJson(event), Event.class));
    }

    @Test
    void decisionTraceRoundTrip() {
        DecisionTrace trace = new DecisionTrace("R1", List.of(
                new OfferEvaluation("ZoneStandard", "ZoneStandard-04", 6.0, 0.42, "NOT_SELECTED", null),
                new OfferEvaluation("ZonePremium", "ZonePremium-02", 9.0, 0.31, "SELECTED", null),
                new OfferEvaluation("ZoneElectric", "ZoneElectric-01", 15.0, null, "ELIMINATED", "BUDGET_EXCEEDED")),
                "ZonePremium", "ZonePremium retenue : meilleur score");
        assertEquals(trace, JsonUtil.fromJson(JsonUtil.toJson(trace), DecisionTrace.class));
    }
}
