package smartparking.models;

/** Réponse de l'assistant à une phrase libre : demande comprise, source (LLM ou FALLBACK) et latence. */
public record NlParseReply(ParkingRequest request, String source, long latencyMs) { }
