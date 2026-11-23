package smartparking.services;

import smartparking.models.Alternative;
import smartparking.models.ParkingConfig;
import smartparking.models.ParkingRequest;
import smartparking.models.RefusalReason;
import smartparking.models.ZoneType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Construit les alternatives proposées à un véhicule refusé (§10) :
 * estimation d'attente quand les zones compatibles sont pleines, durée réduite quand le budget est trop juste.
 * Une alternative « autre zone compatible » n'existe pas tant que les zones spécialisées sont strictes
 * (elle viendra avec la règle de repli exceptionnel ou un second parking).
 * Java pur, sans dépendance à JADE.
 */
public class AlternativeAdvisor {

    private final List<ParkingConfig.ZoneConfig> zones;
    private final double maxSurcharge;

    public AlternativeAdvisor(ParkingConfig config) {
        this(config.zones(), java.util.Arrays.stream(config.pricing().surcharges()).max().orElse(0.0));
    }

    public AlternativeAdvisor(List<ParkingConfig.ZoneConfig> zones, double maxSurcharge) {
        this.zones = List.copyOf(zones);
        this.maxSurcharge = maxSurcharge;
    }

    /** Une zone est compatible si elle correspond exactement aux besoins du véhicule (zones spécialisées strictes). */
    public static boolean isCompatible(ParkingRequest request, ZoneType zoneType) {
        return request.requiresCharging() == (zoneType == ZoneType.ELECTRIC)
                && request.requiresPmr() == (zoneType == ZoneType.PMR);
    }

    /**
     * @param occupiedUntilByZone fins de stationnement connues (minutes simulées), par zone
     * @param nowSimMin           instant simulé actuel
     */
    public List<Alternative> advise(ParkingRequest request, RefusalReason reason,
                                    Map<String, List<Double>> occupiedUntilByZone,
                                    double nowSimMin) {
        List<ParkingConfig.ZoneConfig> compatible = zones.stream()
                .filter(z -> isCompatible(request, z.type())).toList();
        List<Alternative> alternatives = new ArrayList<>();

        if (reason == RefusalReason.ZONE_FULL) {
            String bestZone = null;
            double soonest = Double.MAX_VALUE;
            for (ParkingConfig.ZoneConfig zone : compatible) {
                for (double end : occupiedUntilByZone.getOrDefault(zone.zoneId(), List.of())) {
                    if (end > nowSimMin && end - nowSimMin < soonest) {
                        soonest = end - nowSimMin;
                        bestZone = zone.zoneId();
                    }
                }
            }
            if (bestZone != null) {
                alternatives.add(new Alternative(Alternative.WAIT_ESTIMATE, bestZone, soonest,
                        String.format(Locale.ROOT, "une place se libère dans environ %.0f min dans %s",
                                soonest, bestZone)));
            }
        }

        if (reason == RefusalReason.BUDGET_EXCEEDED) {
            compatible.stream().min(Comparator.comparingDouble(ParkingConfig.ZoneConfig::basePricePerHour))
                    .ifPresent(cheapest -> {
                        // prudence : on suppose la majoration maximale pour que la suggestion soit sûre
                        double hours = Math.floor(request.maxBudget()
                                / (cheapest.basePricePerHour() * (1.0 + maxSurcharge)) * 2.0) / 2.0;
                        if (hours >= 0.5 && hours < request.duration()) {
                            alternatives.add(new Alternative(Alternative.REDUCED_DURATION, cheapest.zoneId(), hours,
                                    String.format(Locale.ROOT, "avec votre budget, %s est possible pour %.1f h",
                                            cheapest.zoneId(), hours)));
                        }
                    });
        }
        return alternatives;
    }
}