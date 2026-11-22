package smartparking.models;

/** Résultat d'une attribution réussie (contenu de l'INFORM final vers le véhicule). */
public record Allocation(
        String requestId,
        String vehicleId,
        String zoneId,
        String slotId,
        double finalPrice,
        double score,
        double startSimMin,
        double endSimMin) {
}
