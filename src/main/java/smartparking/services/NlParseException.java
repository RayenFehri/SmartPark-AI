package smartparking.services;

/** Échec de compréhension d'une demande en langage naturel. */
public class NlParseException extends Exception {

    public NlParseException(String message) {
        super(message);
    }

    public NlParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
