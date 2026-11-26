package smartparking.models;

/** Réponse de l'assistant à une question sur une décision. source : REACT (LLM + outils) ou TEMPLATE. */
public record ExplainReply(String answer, String source, int steps) { }
