package smartparking.services;

/** Accès minimal à un LLM : permet de remplacer Mistral par un faux client dans les tests. */
public interface LlmClient {

    /** Vrai si le client peut être appelé (clé API présente). */
    boolean isAvailable();

    /** Réponse texte à (system, user). jsonMode demande un objet JSON. */
    String complete(String system, String user, boolean jsonMode) throws NlParseException;
}
