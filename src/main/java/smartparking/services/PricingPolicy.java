package smartparking.services;

import smartparking.models.ParkingConfig;
import smartparking.models.Slot;
import smartparking.models.SlotState;

import java.util.List;

/**
 * Tarification dynamique : le prix dépend du taux d'occupation de la zone.
 *
 * Paliers (borne basse incluse) avec les valeurs par défaut :
 * [0 %, 40 %) -> +0 %   [40 %, 70 %) -> +10 %   [70 %, 90 %) -> +20 %   [90 %, 100 %] -> +30 %
 *
 * Classe en Java pur : aucune dépendance à JADE.
 */
public class PricingPolicy {

    private final double[] thresholds;
    private final double[] surcharges;

    /**
     * @param thresholds seuils d'occupation croissants, par exemple {0.4, 0.7, 0.9}
     * @param surcharges majorations associées : exactement un élément de plus que les seuils
     */
    public PricingPolicy(double[] thresholds, double[] surcharges) {
        if (thresholds == null || surcharges == null || surcharges.length != thresholds.length + 1) {
            throw new IllegalArgumentException(
                    "il faut exactement une majoration de plus que de seuils");
        }
        this.thresholds = thresholds.clone();
        this.surcharges = surcharges.clone();
    }

    public PricingPolicy(ParkingConfig.Pricing pricing) {
        this(pricing.thresholds(), pricing.surcharges());
    }

    /**
     * Taux d'occupation = (places RESERVED + OCCUPIED) / (places totales - OUT_OF_SERVICE).
     * Si aucune place n'est utilisable (zone entièrement hors service), retourne 1.0.
     */
    public double occupancyRate(List<Slot> slots) {
        int usable = 0;
        int used = 0;
        for (Slot slot : slots) {
            SlotState state = slot.getState();
            if (state == SlotState.OUT_OF_SERVICE) {
                continue;
            }
            usable++;
            if (state == SlotState.RESERVED || state == SlotState.OCCUPIED) {
                used++;
            }
        }
        if (usable == 0) {
            return 1.0;
        }
        return (double) used / usable;
    }

    /** Majoration (par exemple 0.1 pour +10 %) correspondant à un taux d'occupation. */
    public double surcharge(double occupancyRate) {
        for (int i = 0; i < thresholds.length; i++) {
            if (occupancyRate < thresholds[i]) {
                return surcharges[i];
            }
        }
        return surcharges[surcharges.length - 1];
    }

    /** Prix horaire majoré, arrondi au centime. */
    public double unitPrice(double basePricePerHour, double occupancyRate) {
        return round2(basePricePerHour * (1.0 + surcharge(occupancyRate)));
    }

    /** Prix total pour la durée demandée, arrondi au centime. */
    public double totalPrice(double basePricePerHour, double occupancyRate, double durationHours) {
        return round2(basePricePerHour * (1.0 + surcharge(occupancyRate)) * durationHours);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}