package smartparking.models;

import java.util.List;
import java.util.Map;

/**
 * Configuration globale lue dans config/parking.json.
 * Toutes les valeurs numériques sont des paramètres expérimentaux modifiables.
 */
public record ParkingConfig(
        Strategy strategy,
        long seed,
        double timeScale,
        int cnpTimeoutMs,
        int holdTtlMs,
        int heartbeatMs,
        int downAfterMissed,
        int queueMaxWaitSimMin,
        Pricing pricing,
        Map<String, Weights> weights,
        List<ZoneConfig> zones) {

    /** Seuils d'occupation (3 valeurs) et majorations associées (4 valeurs). */
    public record Pricing(double[] thresholds, double[] surcharges) { }

    /** Poids du score : distance (wD), prix (wP), gaspillage de ressource rare (wW). */
    public record Weights(double wD, double wP, double wW) { }

    /** Description d'une zone : nombre de places, tarif de base, plage de distances à la sortie. */
    public record ZoneConfig(
            String zoneId,
            ZoneType type,
            int slotCount,
            double basePricePerHour,
            double minDistance,
            double maxDistance) { }

    public int totalSlots() {
        return zones.stream().mapToInt(ZoneConfig::slotCount).sum();
    }
}
