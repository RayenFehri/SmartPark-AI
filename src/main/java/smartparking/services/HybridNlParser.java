package smartparking.services;

import smartparking.models.ParkingRequest;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stratégie « LLM d'abord, règles ensuite » : si Mistral est indisponible, échoue ou renvoie
 * un JSON invalide, la demande est traitée par le FallbackParser. Les compteurs servent aux
 * métriques (taux de repli) du rapport.
 */
public final class HybridNlParser implements NlParser {

    /** Résultat détaillé : request vaut null si les deux parseurs échouent ; llmError est null si le LLM n'a pas échoué. */
    public record Detailed(ParkingRequest request, String source, long latencyMs, String llmError) { }

    public static final String SOURCE_LLM = "LLM";
    public static final String SOURCE_FALLBACK = "FALLBACK";
    public static final String SOURCE_NONE = "NONE";

    private final NlParser llm;   // peut être null (pas de clé)
    private final NlParser fallback;
    private final AtomicInteger llmOk = new AtomicInteger();
    private final AtomicInteger llmFailed = new AtomicInteger();
    private final AtomicInteger fallbackUsed = new AtomicInteger();

    public HybridNlParser(NlParser llm, NlParser fallback) {
        this.llm = llm;
        this.fallback = fallback;
    }

    @Override
    public ParkingRequest parse(String text) throws NlParseException {
        Detailed d = parseDetailed(text);
        if (d.request() == null) {
            throw new NlParseException("demande incompréhensible");
        }
        return d.request();
    }

    /** Comme parse, mais indique la source utilisée, la latence mesurée et l'erreur éventuelle du LLM. */
    public Detailed parseDetailed(String text) {
        long start = System.nanoTime();
        String llmError = null;
        if (llm != null) {
            try {
                ParkingRequest r = llm.parse(text);
                llmOk.incrementAndGet();
                return new Detailed(r, SOURCE_LLM, elapsedMs(start), null);
            } catch (NlParseException e) {
                llmFailed.incrementAndGet();
                llmError = e.getMessage();
            }
        }
        fallbackUsed.incrementAndGet();
        try {
            return new Detailed(fallback.parse(text), SOURCE_FALLBACK, elapsedMs(start), llmError);
        } catch (NlParseException e) {
            return new Detailed(null, SOURCE_NONE, elapsedMs(start),
                    llmError == null ? e.getMessage() : llmError);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public int llmOk() { return llmOk.get(); }
    public int llmFailed() { return llmFailed.get(); }
    public int fallbackUsed() { return fallbackUsed.get(); }
}
