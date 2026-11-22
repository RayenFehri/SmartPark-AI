package smartparking.models;

/** Refus motivé (contenu du REFUSE d'une zone, ou du REFUSE du Manager vers le véhicule). */
public record Refusal(
        String requestId,
        String zoneId,
        RefusalReason reason) {
}
