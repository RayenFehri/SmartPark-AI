package smartparking.models;

/**
 * Évaluation d'une offre par le Manager, conservée dans la DecisionTrace.
 * status : SELECTED, NOT_SELECTED ou ELIMINATED ; reason explique l'élimination.
 */
public record OfferEvaluation(
        String zoneId,
        String slotId,
        double totalPrice,
        Double score,
        String status,
        String reason) {
}
