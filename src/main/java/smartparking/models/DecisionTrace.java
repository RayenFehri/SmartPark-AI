package smartparking.models;

import java.util.List;

/**
 * Trace structurée d'une décision d'allocation. Source unique des explications
 * (gabarit déterministe ou reformulation par le LLM) : le LLM n'invente aucun fait.
 */
public record DecisionTrace(
        String requestId,
        List<OfferEvaluation> evaluations,
        String selectedZoneId,
        String summary) {
}
