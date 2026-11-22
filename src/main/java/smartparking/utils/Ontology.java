package smartparking.utils;

/** Constantes partagées par tous les agents pour les messages ACL. */
public final class Ontology {

    private Ontology() { }

    /** Ontologie des messages métier entre agents. */
    public static final String NAME = "smartpark-ontology";
    /** Langage de contenu des messages ACL : JSON (sérialisé avec Gson). */
    public static final String LANGUAGE = "JSON";

    /** Ontologie des échanges texte libre entre véhicule et assistant LLM. */
    public static final String NL_ASSIST = "nl-assist";
    /** Ontologie des messages internes (retour d'un appel HTTP asynchrone vers l'agent). */
    public static final String INTERNAL = "internal";
    /** Ontologie des requêtes d'outils de l'assistant LLM vers le Manager. */
    public static final String TOOLS = "smartpark-tools";

    /** Service enregistré par les zones au DF. */
    public static final String ZONE_SERVICE_TYPE = "parking-zone";
    /** Propriété du service DF indiquant le type de zone. */
    public static final String ZONE_TYPE_PROPERTY = "zoneType";

    // Contenus des messages de contrôle (INFORM ou REQUEST simples)
    public static final String FAULT_ON = "FAULT_ON";
    public static final String FAULT_OFF = "FAULT_OFF";
    public static final String END_OF_RUN = "END_OF_RUN";
    public static final String ZONE_DOWN = "ZONE_DOWN";
    public static final String ZONE_UP = "ZONE_UP";
    public static final String SLOT_RELEASED = "SLOT_RELEASED";
    public static final String HEARTBEAT = "HEARTBEAT";
}
