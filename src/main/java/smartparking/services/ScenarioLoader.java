package smartparking.services;

import smartparking.models.ParkingRequest;
import smartparking.models.PreferredLocation;
import smartparking.models.Priority;
import smartparking.models.Scenario;
import smartparking.models.VehicleType;
import smartparking.utils.JsonUtil;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Lecture d'un scénario et génération reproductible des demandes de stationnement. */
public final class ScenarioLoader {

    private ScenarioLoader() { }

    public static Scenario load(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonUtil.fromJson(reader, Scenario.class);
        }
    }

    /**
     * Génère les demandes du scénario, triées par instant d'arrivée.
     * Même scénario + même graine = même liste (java.util.Random seedé).
     * L'ordre des appels à Random est figé : ne pas le modifier sans régénérer les résultats.
     */
    public static List<ParkingRequest> generate(Scenario scenario) {
        Random random = new Random(scenario.seed());
        List<ParkingRequest> requests = new ArrayList<>();

        for (int i = 1; i <= scenario.vehicleCount(); i++) {
            double arrival = random.nextDouble() * scenario.arrivalWindowSimMin();

            double typeDraw = random.nextDouble();
            VehicleType type;
            if (typeDraw < scenario.pmrRatio()) {
                type = VehicleType.PMR;
            } else if (typeDraw < scenario.pmrRatio() + scenario.electricRatio()) {
                type = VehicleType.ELECTRIC;
            } else {
                type = VehicleType.STANDARD;
            }

            double duration = roundHalf(scenario.minDurationHours()
                    + random.nextDouble() * (scenario.maxDurationHours() - scenario.minDurationHours()));
            duration = Math.max(scenario.minDurationHours(), duration);

            double budget = roundHalf(scenario.minBudget()
                    + random.nextDouble() * (scenario.maxBudget() - scenario.minBudget()));

            PreferredLocation location = random.nextDouble() < scenario.nearExitRatio()
                    ? PreferredLocation.NEAR_EXIT : PreferredLocation.ANY;

            Priority priority = type == VehicleType.PMR ? Priority.PMR : Priority.NORMAL;

            requests.add(new ParkingRequest(
                    String.format("%s-R%03d", scenario.name(), i),
                    String.format("Vehicle%d", i),
                    duration,
                    budget,
                    type,
                    type == VehicleType.ELECTRIC,
                    type == VehicleType.PMR,
                    priority,
                    location,
                    Math.round(arrival)));
        }

        requests.sort(Comparator.comparingLong(ParkingRequest::arrivalSimMin)
                .thenComparing(ParkingRequest::requestId));
        return requests;
    }

    /** Arrondi au demi le plus proche (durées en demi-heures, budgets en demi-dinars). */
    private static double roundHalf(double value) {
        return Math.round(value * 2.0) / 2.0;
    }
}
