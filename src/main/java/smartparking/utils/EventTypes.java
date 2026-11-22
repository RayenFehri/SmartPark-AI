package smartparking.utils;

/** Types d'événements journalisés par le MonitorAgent (annexe A8). */
public final class EventTypes {

    private EventTypes() { }

    // Demande
    public static final String REQUEST_SENT = "REQUEST_SENT";
    public static final String QUEUED = "QUEUED";
    public static final String ALLOCATED = "ALLOCATED";
    public static final String REFUSED = "REFUSED";
    public static final String FAILED = "FAILED";

    // Négociation
    public static final String CFP_SENT = "CFP_SENT";
    public static final String PROPOSE = "PROPOSE";
    public static final String REFUSE = "REFUSE";
    public static final String ACCEPT = "ACCEPT";
    public static final String REJECT = "REJECT";
    public static final String CONFLICT = "CONFLICT";
    public static final String TIMEOUT = "TIMEOUT";

    // Ressources
    public static final String HOLD_SET = "HOLD_SET";
    public static final String HOLD_EXPIRED = "HOLD_EXPIRED";
    public static final String SLOT_RELEASED = "SLOT_RELEASED";
    public static final String PRICE_CHANGED = "PRICE_CHANGED";

    // Santé
    public static final String HEARTBEAT = "HEARTBEAT";
    public static final String ZONE_DOWN = "ZONE_DOWN";
    public static final String ZONE_UP = "ZONE_UP";
    public static final String FAULT_ON = "FAULT_ON";
    public static final String FAULT_OFF = "FAULT_OFF";

    // LLM
    public static final String LLM_CALL = "LLM_CALL";
    public static final String LLM_FALLBACK = "LLM_FALLBACK";
    public static final String TOOL_CALL = "TOOL_CALL";
    public static final String TOOL_REFUSED = "TOOL_REFUSED";
}
