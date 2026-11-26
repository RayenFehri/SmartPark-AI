package smartparking.services;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import smartparking.models.ParkingRequest;
import smartparking.models.PreferredLocation;
import smartparking.models.Priority;
import smartparking.models.VehicleType;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ParsingTest {

    private final FallbackParser fb = new FallbackParser();

    @Test
    void fallbackElectricWithBudgetAndExit() throws Exception {
        ParkingRequest r = fb.parse("Je veux une place avec recharge électrique pour 3 heures, budget 12 DT, près de la sortie");
        assertEquals(3.0, r.duration(), 1e-9);
        assertEquals(12.0, r.maxBudget(), 1e-9);
        assertEquals(VehicleType.ELECTRIC, r.vehicleType());
        assertTrue(r.requiresCharging());
        assertEquals(PreferredLocation.NEAR_EXIT, r.preferredLocation());
    }

    @Test
    void fallbackPmrUrgentAndDurations() throws Exception {
        ParkingRequest r = fb.parse("Personne à mobilité réduite, 1h30, 20 dinars");
        assertEquals(1.5, r.duration(), 1e-9);
        assertEquals(VehicleType.PMR, r.vehicleType());
        assertEquals(Priority.PMR, r.priority());
        ParkingRequest u = fb.parse("C'est urgent, 45 min, 8 DT");
        assertEquals(Priority.URGENT, u.priority());
        assertEquals(0.75, u.duration(), 1e-9);
        assertEquals(0.5, fb.parse("une demi-heure s'il vous plait").duration(), 1e-9);
    }

    @Test
    void fallbackDefaultsAndErrors() throws Exception {
        ParkingRequest r = fb.parse("2 heures");
        assertEquals(VehicleType.STANDARD, r.vehicleType());
        assertEquals(FallbackParser.UNLIMITED_BUDGET, r.maxBudget(), 1e-9);
        assertThrows(NlParseException.class, () -> fb.parse("bonjour"));
        assertThrows(NlParseException.class, () -> fb.parse("  "));
    }

    @Test
    void validatorAcceptsFencedJson() throws Exception {
        String raw = "Voici :\n```json\n{\"duration\":2,\"maxBudget\":10,\"vehicleType\":\"standard\"}\n```";
        ParkingRequest r = JsonValidator.validate(raw);
        assertEquals(VehicleType.STANDARD, r.vehicleType());
        assertEquals(Priority.NORMAL, r.priority());
        assertEquals(PreferredLocation.ANY, r.preferredLocation());
    }

    @Test
    void validatorRejectsBadInputs() {
        String[] bad = {
                "", "pas de json", "{\"duration\":2}",
                "{\"duration\":-1,\"maxBudget\":10,\"vehicleType\":\"STANDARD\"}",
                "{\"duration\":2,\"maxBudget\":10,\"vehicleType\":\"BUS\"}",
                "{\"duration\":\"abc\",\"maxBudget\":10,\"vehicleType\":\"STANDARD\"}",
                "{\"duration\":2,\"maxBudget\":10,\"vehicleType\":\"STANDARD\",\"requiresPmr\":true,\"requiresCharging\":true}",
                "{\"duration\":2,\"maxBudget\":10,\"vehicleType\":\"ELECTRIC\",\"requiresPmr\":true}",
                "{\"duration\":2,\"maxBudget\":99999,\"vehicleType\":\"STANDARD\"}",
                "{\"duration\":2,\"maxBudget\":10,\"vehicleType\":\"STANDARD\",\"priority\":\"HIGH\"}",
                "{\"duration\":2,\"maxBudget\":10,\"vehicleType\":\"STANDARD\",\"requiresPmr\":\"oui\"}"
        };
        for (String b : bad) {
            assertThrows(NlParseException.class, () -> JsonValidator.validate(b), b);
        }
    }

    private static String reply(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
        choices.add(choice);
        JsonObject root = new JsonObject();
        root.add("choices", choices);
        return root.toString();
    }

    @Test
    void mistralParsesViaTransport() throws Exception {
        MistralService s = new MistralService("k", "m", Duration.ofSeconds(1),
                (url, key, body, t) -> {
                    assertEquals("k", key);
                    assertTrue(body.contains("json_object"));
                    return reply("{\"duration\":1.5,\"maxBudget\":9,\"vehicleType\":\"ELECTRIC\",\"requiresCharging\":true}");
                });
        ParkingRequest r = s.parse("n'importe quoi");
        assertEquals(1.5, r.duration(), 1e-9);
        assertTrue(r.requiresCharging());
    }

    @Test
    void mistralWithoutKeyOrWithBadReplyFails() {
        MistralService noKey = new MistralService("", "m", Duration.ofSeconds(1), (u, k, b, t) -> "");
        assertFalse(noKey.isAvailable());
        assertThrows(NlParseException.class, () -> noKey.parse("x"));
        MistralService junk = new MistralService("k", "m", Duration.ofSeconds(1), (u, k, b, t) -> "<html>");
        assertThrows(NlParseException.class, () -> junk.parse("x"));
        MistralService down = new MistralService("k", "m", Duration.ofSeconds(1),
                (u, k, b, t) -> { throw new IOException("timeout"); });
        assertThrows(NlParseException.class, () -> down.parse("x"));
    }

    @Test
    void hybridFallsBackAndCounts() throws Exception {
        MistralService down = new MistralService("k", "m", Duration.ofSeconds(1),
                (u, k, b, t) -> { throw new IOException("timeout"); });
        HybridNlParser h = new HybridNlParser(down, fb);
        ParkingRequest r = h.parse("2 heures, 5 DT");
        assertEquals(2.0, r.duration(), 1e-9);
        assertEquals(1, h.llmFailed());
        assertEquals(1, h.fallbackUsed());
        assertEquals(0, h.llmOk());
        assertEquals(1.0, new HybridNlParser(null, fb).parse("1h").duration(), 1e-9);
    }
}