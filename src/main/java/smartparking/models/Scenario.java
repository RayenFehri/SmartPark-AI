package smartparking.models;

import java.util.List;

/**
 * Description d'un scénario de simulation (config/scenarios/*.json).
 * Avec la même graine, la génération des demandes est identique (reproductibilité).
 */
public record Scenario(
        String name,
        long seed,
        int vehicleCount,
        double arrivalWindowSimMin,
        double electricRatio,
        double pmrRatio,
        double nearExitRatio,
        double minDurationHours,
        double maxDurationHours,
        double minBudget,
        double maxBudget,
        List<FaultEvent> faults) {

    /** Panne injectée sur une zone : début et durée en minutes simulées. */
    public record FaultEvent(String zoneId, double atSimMin, double durationSimMin) { }
}
