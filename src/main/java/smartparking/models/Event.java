package smartparking.models;

/** Événement envoyé au MonitorAgent (types dans smartparking.utils.EventTypes). */
public record Event(
        long timestampRealMs,
        double timestampSimMin,
        String type,
        String agent,
        String requestId,
        String zoneId,
        String details) {
}
