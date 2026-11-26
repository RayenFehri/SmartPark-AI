package smartparking.services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import smartparking.models.DecisionTrace;
import smartparking.models.Event;
import smartparking.models.OfferEvaluation;
import smartparking.models.ParkingConfig;
import smartparking.models.ParkingRequest;
import smartparking.models.PreferredLocation;
import smartparking.models.Priority;
import smartparking.models.Scenario;
import smartparking.models.VehicleType;
import smartparking.models.ZoneType;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Étape 7 : phrases en français, outils de l'assistant, boucle ReAct, métriques LLM. */
class LlmSupportTest {

    // ------------------------------------------------------------ phrases

    @Test
    void generatedSentencesAreUnderstoodByTheRulesForManySeeds() {
        FallbackParser parser = new FallbackParser();
        int checked = 0;
        for (long seed = 1; seed <= 30; seed++) {
            Scenario s1 = new Scenario("S1", seed, 40, 120, 0.3, 0.1, 0.4, 1.0, 4.0, 5.0, 20.0, List.of());
            Scenario s3 = new Scenario("S3", seed, 60, 30, 0.25, 0.1, 0.4, 3.0, 6.0, 10.0, 30.0, List.of());
            for (Scenario scenario : List.of(s1, s3)) {
                for (ParkingRequest r : ScenarioLoader.generate(scenario)) {
                    String sentence = NlSentenceGenerator.sentence(r);
                    try {
                        List<String> diff = NlFidelity.mismatches(r, parser.parse(sentence));
                        assertTrue(diff.isEmpty(), sentence + " -> " + diff);
                    } catch (NlParseException e) {
                        fail("phrase non comprise : " + sentence);
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked > 2000);
    }

    @Test
    void edgeCaseSentences() throws Exception {
        FallbackParser parser = new FallbackParser();
        for (double hours : new double[] {0.5, 1.0, 1.5, 2.0, 2.5, 12.0}) {
            for (Priority priority : Priority.values()) {
                for (int i = 0; i < 4; i++) {
                    ParkingRequest r = new ParkingRequest("S9-R00" + i, "V" + i, hours, 7.5, VehicleType.STANDARD,
                            false, false, priority == Priority.PMR ? Priority.NORMAL : priority,
                            PreferredLocation.NEAR_EXIT, 0);
                    String sentence = NlSentenceGenerator.sentence(r);
                    assertTrue(NlFidelity.mismatches(r, parser.parse(sentence)).isEmpty(), sentence);
                }
            }
        }
    }

    @Test
    void fidelityDetectsWrongFields() {
        ParkingRequest a = new ParkingRequest("R", "V", 2.0, 10.0, VehicleType.STANDARD, false, false,
                Priority.NORMAL, PreferredLocation.ANY, 0);
        ParkingRequest b = new ParkingRequest("R", "V", 3.0, 10.0, VehicleType.ELECTRIC, true, false,
                Priority.NORMAL, PreferredLocation.ANY, 5);
        List<String> diff = NlFidelity.mismatches(a, b);
        assertTrue(diff.contains("duration") && diff.contains("vehicleType") && diff.contains("requiresCharging"));
        assertEquals(3, diff.size());      // l'arrivée et les identifiants ne comptent pas
        assertEquals(List.of("all"), NlFidelity.mismatches(a, null));
    }

    @Test
    void hybridDetailedReportsSourceAndErrors() {
        MistralService down = new MistralService("k", "m", Duration.ofSeconds(1),
                (u, k, b, t) -> { throw new IOException("HTTP 429"); });
        HybridNlParser hybrid = new HybridNlParser(down, new FallbackParser());
        HybridNlParser.Detailed ok = hybrid.parseDetailed("2 heures, 5 DT");
        assertEquals("FALLBACK", ok.source());
        assertTrue(ok.llmError().contains("429"));
        assertEquals(2.0, ok.request().duration(), 1e-9);

        HybridNlParser.Detailed none = hybrid.parseDetailed("bonjour");
        assertEquals("NONE", none.source());
        assertTrue(none.request() == null);

        HybridNlParser rulesOnly = new HybridNlParser(null, new FallbackParser());
        assertTrue(rulesOnly.parseDetailed("1h").llmError() == null);
    }

    // ------------------------------------------------------------ outils

    private static ManagerView view(int occupiedStandard, int queuePosition) {
        List<ParkingConfig.ZoneConfig> zones = List.of(
                new ParkingConfig.ZoneConfig("ZoneStandard", ZoneType.STANDARD, 20, 2.0, 60, 150),
                new ParkingConfig.ZoneConfig("ZoneElectric", ZoneType.ELECTRIC, 8, 3.0, 40, 100));
        DecisionTrace trace = new DecisionTrace("S1-R001",
                List.of(new OfferEvaluation("ZoneStandard", "ZoneStandard-01", 6.0, 0.31, "SELECTED", null)),
                "ZoneStandard", "Retenue : ZoneStandard, 6.00 DT.");
        return new ManagerView() {
            public double nowSimMin() { return 10; }
            public Optional<DecisionTrace> decision(String id) {
                return id.equals("S1-R001") ? Optional.of(trace) : Optional.empty();
            }
            public List<ParkingConfig.ZoneConfig> zones() { return zones; }
            public int occupiedSlots(String zoneId) { return zoneId.equals("ZoneStandard") ? occupiedStandard : 0; }
            public boolean isDown(String zoneId) { return zoneId.equals("ZoneElectric"); }
            public int queuePosition(String id) { return queuePosition; }
            public int queueSize() { return 3; }
        };
    }

    private static ToolRegistry registry(int occupiedStandard, int queuePosition) {
        return new ToolRegistry(view(occupiedStandard, queuePosition),
                new PricingPolicy(new double[] {0.4, 0.7, 0.9}, new double[] {0.0, 0.1, 0.2, 0.3}));
    }

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void onlyFiveToolsAreAllowed() {
        assertEquals(5, ToolRegistry.NAMES.size());
        assertTrue(ToolRegistry.isAllowed("get_decision"));
        assertFalse(ToolRegistry.isAllowed("release_slot"));
        assertFalse(ToolRegistry.isAllowed(null));
        ToolRegistry r = registry(0, -1);
        assertTrue(r.validate("delete_zone", args("{}")).contains("non autorisé"));
        assertTrue(r.execute("reserve_slot", args("{}")).contains("error"));
    }

    @Test
    void argumentsAreValidated() {
        ToolRegistry r = registry(0, -1);
        assertTrue(r.validate("get_decision", args("{}")) != null);
        assertTrue(r.validate("get_decision", args("{\"requestId\":\"S1 ; DROP\"}")) != null);
        assertTrue(r.validate("get_decision", args("{\"requestId\":\"S1-R001\"}")) == null);
        assertTrue(r.validate("get_zone_status", args("{\"zoneId\":\"ZoneInconnue\"}")) != null);
        assertTrue(r.validate("estimate_price", args("{\"zoneId\":\"ZoneStandard\",\"durationHours\":-2}")) != null);
        assertTrue(r.validate("estimate_price", args("{\"zoneId\":\"ZoneStandard\",\"durationHours\":99}")) != null);
        assertTrue(r.validate("estimate_price", args("{\"zoneId\":\"ZoneStandard\",\"durationHours\":\"deux\"}")) != null);
        assertTrue(r.validate("estimate_price", args("{\"zoneId\":\"ZoneStandard\",\"durationHours\":2.5}")) == null);
        assertTrue(r.validate("list_zones", args("{}")) == null);
    }

    @Test
    void toolsReturnObservationsFromManagerState() {
        ToolRegistry r = registry(10, 2);          // ZoneStandard à 50 % -> +10 %
        String status = r.execute("get_zone_status", args("{\"zoneId\":\"ZoneStandard\"}"));
        assertTrue(status.contains("\"free\":10") && status.contains("\"occupancyPct\":50"), status);
        assertTrue(status.contains("\"currentUnitPrice\":2.2"), status);

        String price = r.execute("estimate_price", args("{\"zoneId\":\"ZoneStandard\",\"durationHours\":3}"));
        assertTrue(price.contains("\"totalPrice\":6.6"), price);

        String zones = r.execute("list_zones", args("{}"));
        assertTrue(zones.contains("ZoneElectric") && zones.contains("\"down\":true"), zones);

        String decision = r.execute("get_decision", args("{\"requestId\":\"S1-R001\"}"));
        assertTrue(decision.contains("\"selectedZone\":\"ZoneStandard\"") && decision.contains("SELECTED"), decision);
        assertTrue(r.execute("get_decision", args("{\"requestId\":\"S1-R999\"}")).contains("aucune décision"));

        assertTrue(r.execute("get_queue_position", args("{\"requestId\":\"X\"}")).contains("\"position\":2"));
        assertTrue(registry(0, -1).execute("get_queue_position", args("{\"requestId\":\"X\"}"))
                .contains("\"inQueue\":false"));
        assertTrue(registry(0, 0).execute("get_queue_position", args("{\"requestId\":\"X\"}"))
                .contains("nouvelle négociation"));
    }

    @Test
    void fallbackExplanationUsesTheDecisionSummaryOnly() {
        String obs = registry(0, -1).execute("get_decision", args("{\"requestId\":\"S1-R001\"}"));
        assertEquals("Retenue : ZoneStandard, 6.00 DT.", ToolRegistry.explanationFromDecision(obs));
        String none = registry(0, -1).execute("get_decision", args("{\"requestId\":\"S1-R999\"}"));
        assertTrue(ToolRegistry.explanationFromDecision(none).contains("pas d'explication"));
        assertTrue(ToolRegistry.explanationFromDecision("n'importe quoi").contains("pas pu lire"));
    }

    // ------------------------------------------------------------ ReAct

    @Test
    void reactToolCallThenFinalAnswer() {
        ReActSession s = new ReActSession("Pourquoi ?", "S1-R001");
        ReActSession.Step first = s.onLlmOutput(
                "```json\n{\"thought\":\"je regarde\",\"action\":\"get_decision\",\"args\":{\"requestId\":\"S1-R001\"}}\n```");
        assertTrue(first instanceof ReActSession.ToolCall);
        ReActSession.ToolCall call = (ReActSession.ToolCall) first;
        assertEquals("get_decision", call.tool());
        assertEquals("S1-R001", call.args().get("requestId").getAsString());

        s.onObservation("get_decision", "{\"summary\":\"x\"}");
        assertTrue(s.userPrompt().contains("Observation (get_decision)"));
        ReActSession.Step done = s.onLlmOutput("{\"final\":\"Parce que c'était la moins chère.\"}");
        assertTrue(done instanceof ReActSession.Final);
        assertEquals(1, s.toolCalls());
        assertFalse(s.exhausted());
    }

    @Test
    void reactRefusesAnswerWithoutObservation() {
        ReActSession s = new ReActSession("Pourquoi ?", "S1-R001");
        ReActSession.Step step = s.onLlmOutput("{\"final\":\"Je pense que c'est le prix.\"}");
        assertTrue(step instanceof ReActSession.Invalid);
        assertTrue(s.userPrompt().contains("invalide"));
    }

    @Test
    void reactGivesUpAfterInvalidOutputsOrTooManyCalls() {
        ReActSession s = new ReActSession("Pourquoi ?", "S1-R001");
        s.onLlmOutput("blabla");
        assertFalse(s.exhausted());
        s.onLlmOutput("{\"truc\":1}");
        assertTrue(s.exhausted());

        ReActSession loop = new ReActSession("Pourquoi ?", "S1-R001");
        for (int i = 0; i < ReActSession.MAX_LLM_CALLS; i++) {
            loop.onLlmOutput("{\"action\":\"list_zones\",\"args\":{}}");
            loop.onObservation("list_zones", "{}");
        }
        assertTrue(loop.exhausted());
    }

    @Test
    void reactTruncatesLongAnswers() {
        ReActSession s = new ReActSession("Pourquoi ?", "R");
        s.onLlmOutput("{\"action\":\"list_zones\"}");
        s.onObservation("list_zones", "{}");
        ReActSession.Final f = (ReActSession.Final) s.onLlmOutput("{\"final\":\"" + "a".repeat(2000) + "\"}");
        assertTrue(f.text().length() <= ReActSession.MAX_ANSWER_CHARS + 1);
    }

    // ------------------------------------------------------------ métriques

    @Test
    void metricsCountLlmEvents() {
        MetricsCollector c = new MetricsCollector();
        c.onEvent(new Event(1, 0, "LLM_CALL", "assistant", "R1", null,
                "{\"purpose\":\"parse\",\"source\":\"LLM\",\"latencyMs\":400,\"mismatches\":[]}"));
        c.onEvent(new Event(2, 0, "LLM_CALL", "assistant", "R2", null,
                "{\"purpose\":\"parse\",\"source\":\"FALLBACK\",\"latencyMs\":600,\"mismatches\":[\"duration\"]}"));
        c.onEvent(new Event(3, 0, "LLM_FALLBACK", "assistant", "R2", null, "{\"purpose\":\"parse\"}"));
        c.onEvent(new Event(4, 0, "LLM_CALL", "assistant", "R1", null,
                "{\"purpose\":\"react\",\"latencyMs\":1000,\"ok\":true}"));
        c.onEvent(new Event(5, 0, "TOOL_CALL", "manager", "R1", null, "{\"tool\":\"get_decision\"}"));
        c.onEvent(new Event(6, 0, "TOOL_REFUSED", "manager", "R1", null, "{\"tool\":\"x\"}"));
        MetricsCollector.LlmStats s = c.llmStats();
        assertEquals(2, s.parseCalls());
        assertEquals(1, s.parsedByRules());
        assertEquals(1, s.mismatchedRequests());
        assertEquals(1, s.reactCalls());
        assertEquals(1, s.fallbacks());
        assertEquals(1, s.toolCalls());
        assertEquals(1, s.toolRefused());
        assertEquals(500.0, s.avgParseLatencyMs(), 1e-9);
        assertEquals(1000.0, s.avgReactLatencyMs(), 1e-9);
        assertFalse(s.isEmpty());
        assertTrue(new MetricsCollector().llmStats().isEmpty());
    }
}
