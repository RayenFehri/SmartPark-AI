package smartparking.services;

import org.junit.jupiter.api.Test;
import smartparking.models.ParkingRequest;
import smartparking.models.Priority;
import smartparking.models.Scenario;
import smartparking.models.VehicleType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioLoaderTest {

    private static final Path DIR = Path.of("config", "scenarios");

    private Scenario s1() throws IOException {
        return ScenarioLoader.load(DIR.resolve("s1_normal.json"));
    }

    @Test
    void sameSeedGivesSameRequests() throws IOException {
        Scenario scenario = s1();
        assertEquals(ScenarioLoader.generate(scenario), ScenarioLoader.generate(scenario));
    }

    @Test
    void differentSeedGivesDifferentRequests() throws IOException {
        Scenario a = s1();
        Scenario b = new Scenario(a.name(), a.seed() + 1, a.vehicleCount(), a.arrivalWindowSimMin(),
                a.electricRatio(), a.pmrRatio(), a.nearExitRatio(), a.minDurationHours(), a.maxDurationHours(),
                a.minBudget(), a.maxBudget(), a.faults());
        assertNotEquals(ScenarioLoader.generate(a), ScenarioLoader.generate(b));
    }

    @Test
    void generatedRequestsRespectScenarioConstraints() throws IOException {
        Scenario scenario = s1();
        List<ParkingRequest> requests = ScenarioLoader.generate(scenario);
        assertEquals(scenario.vehicleCount(), requests.size());

        Set<String> ids = new HashSet<>();
        long previousArrival = -1;
        for (ParkingRequest r : requests) {
            assertTrue(ids.add(r.requestId()), "identifiant dupliqué : " + r.requestId());
            assertTrue(r.arrivalSimMin() >= previousArrival, "demandes non triées par arrivée");
            previousArrival = r.arrivalSimMin();
            assertTrue(r.arrivalSimMin() <= Math.round(scenario.arrivalWindowSimMin()));
            assertTrue(r.duration() >= scenario.minDurationHours() && r.duration() <= scenario.maxDurationHours());
            assertTrue(r.maxBudget() >= scenario.minBudget() && r.maxBudget() <= scenario.maxBudget());
            assertEquals(r.vehicleType() == VehicleType.ELECTRIC, r.requiresCharging());
            assertEquals(r.vehicleType() == VehicleType.PMR, r.requiresPmr());
            if (r.vehicleType() == VehicleType.PMR) {
                assertEquals(Priority.PMR, r.priority());
            }
        }
    }

    @Test
    void allScenarioFilesLoadAndGenerate() throws IOException {
        String[] files = {"s1_normal.json", "s2_rush.json", "s3_saturation.json", "s4_panne.json"};
        for (String file : files) {
            Scenario scenario = ScenarioLoader.load(DIR.resolve(file));
            assertEquals(scenario.vehicleCount(), ScenarioLoader.generate(scenario).size(), file);
        }
    }

    @Test
    void breakdownScenarioDeclaresItsFault() throws IOException {
        Scenario s4 = ScenarioLoader.load(DIR.resolve("s4_panne.json"));
        assertEquals(1, s4.faults().size());
        assertEquals("ZoneStandard", s4.faults().get(0).zoneId());
    }
}
