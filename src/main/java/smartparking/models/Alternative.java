package smartparking.models;

/**
 * Proposition faite à un véhicule dont la demande est refusée (cahier des charges, §10).
 * kind : WAIT_ESTIMATE (value = minutes simulées avant une libération) ou
 *        REDUCED_DURATION (value = durée en heures que le budget permet dans la zone zoneId).
 */
public record Alternative(String kind, String zoneId, double value, String description) {

    public static final String WAIT_ESTIMATE = "WAIT_ESTIMATE";
    public static final String REDUCED_DURATION = "REDUCED_DURATION";
}