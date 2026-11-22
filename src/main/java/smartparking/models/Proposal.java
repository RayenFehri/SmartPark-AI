package smartparking.models;

/** Offre d'une zone en réponse à un CFP (contenu du PROPOSE). */
public record Proposal(
        String requestId,
        String zoneId,
        ZoneType zoneType,
        String slotId,
        double unitPrice,
        double totalPrice,
        double distanceToExit,
        double occupancyRate,
        long holdExpiresAtRealMs) {
}
