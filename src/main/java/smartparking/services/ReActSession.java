package smartparking.services;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * État d'une boucle ReAct (Reason + Act) pour répondre à une question : le LLM alterne
 * réflexion, appel d'outil et observation, puis donne sa réponse finale. Java pur : l'agent
 * s'occupe des appels réseau et des messages, cette classe décide seulement de la suite.
 *
 * Garde-fous : au plus MAX_LLM_CALLS appels au LLM, 2 sorties invalides tolérées, et une réponse finale
 * n'est acceptée qu'après au moins une observation (pas de réponse « de mémoire »).
 */
public final class ReActSession {

    public static final int MAX_LLM_CALLS = 5;
    public static final int MAX_INVALID = 2;
    public static final int MAX_ANSWER_CHARS = 600;

    public sealed interface Step permits ToolCall, Final, Invalid { }

    public record ToolCall(String tool, JsonObject args, String thought) implements Step { }

    public record Final(String text) implements Step { }

    public record Invalid(String reason) implements Step { }

    public static final String SYSTEM_PROMPT = """
            Tu es l'assistant d'un parking intelligent. Tu réponds à la question d'un conducteur en utilisant \
            UNIQUEMENT les outils ci-dessous, jamais de mémoire.
            Outils (arguments en JSON) :
            - get_decision {"requestId": "S1-R003"} : décision d'allocation (zone choisie, offres comparées, motifs)
            - get_zone_status {"zoneId": "ZoneStandard"} : occupation, places libres, panne, prix actuel
            - list_zones {} : zones, types, capacités, tarifs de base
            - estimate_price {"zoneId": "ZoneStandard", "durationHours": 2.5} : prix estimé maintenant
            - get_queue_position {"requestId": "S1-R003"} : rang dans la file d'attente
            À chaque étape, réponds avec UN SEUL objet JSON :
            {"thought": "...", "action": "<outil>", "args": {...}}   pour appeler un outil
            {"final": "réponse courte en français"}   quand tu as assez d'informations
            Règles : n'invente aucun chiffre ; cite seulement ce que les observations contiennent ; \
            ne compare les zones que d'après le champ evaluations (score plus petit = meilleur ; ne dis pas qu'une zone \
            est la seule possible si plusieurs offres ont le statut valable) ; \
            une seule action par étape ; réponse finale en 3 phrases au plus.""";

    private final String question;
    private final String requestId;
    private final List<String> transcript = new ArrayList<>();
    private int llmCalls;
    private int invalid;
    private int observations;
    private int toolCalls;

    public ReActSession(String question, String requestId) {
        this.question = question;
        this.requestId = requestId;
    }

    /** Message utilisateur envoyé au LLM : la question et tout ce qui s'est passé jusqu'ici. */
    public String userPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("Question du conducteur (demande ").append(requestId).append(") : ").append(question).append('\n');
        for (String line : transcript) {
            sb.append(line).append('\n');
        }
        sb.append("Étape suivante ?");
        return sb.toString();
    }

    /** Analyse la sortie du LLM et dit ce qu'il faut faire. */
    public Step onLlmOutput(String raw) {
        llmCalls++;
        Step step = parse(raw);
        if (step instanceof Invalid i) {
            invalid++;
            transcript.add("Observation : sortie invalide (" + i.reason() + "). Réponds avec un objet JSON valide.");
        } else if (step instanceof ToolCall t) {
            toolCalls++;
            transcript.add("Action : " + t.tool() + " " + t.args());
        }
        return step;
    }

    /** Résultat d'un outil (ou refus) : il est ajouté à l'historique montré au LLM. */
    public void onObservation(String tool, String observation) {
        observations++;
        transcript.add("Observation (" + tool + ") : " + observation);
    }

    /** Vrai quand il faut renoncer à la boucle et utiliser la réponse de secours. */
    public boolean exhausted() {
        return invalid >= MAX_INVALID || llmCalls >= MAX_LLM_CALLS;
    }

    public int toolCalls() {
        return toolCalls;
    }

    public int llmCalls() {
        return llmCalls;
    }

    private Step parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Invalid("réponse vide");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return new Invalid("pas d'objet JSON");
        }
        JsonObject o;
        try {
            JsonElement root = JsonParser.parseString(raw.substring(start, end + 1));
            if (!root.isJsonObject()) {
                return new Invalid("objet JSON attendu");
            }
            o = root.getAsJsonObject();
        } catch (JsonParseException e) {
            return new Invalid("JSON illisible");
        }
        JsonElement finalAnswer = o.get("final");
        if (finalAnswer != null && finalAnswer.isJsonPrimitive()) {
            String text = finalAnswer.getAsString().trim();
            if (observations == 0) {
                return new Invalid("réponse finale avant toute observation : appelle d'abord un outil");
            }
            if (text.isEmpty()) {
                return new Invalid("réponse finale vide");
            }
            return new Final(text.length() > MAX_ANSWER_CHARS ? text.substring(0, MAX_ANSWER_CHARS) + "…" : text);
        }
        JsonElement action = o.get("action");
        if (action == null || !action.isJsonPrimitive()) {
            return new Invalid("ni action ni final");
        }
        JsonElement args = o.get("args");
        JsonObject argObject = args != null && args.isJsonObject() ? args.getAsJsonObject() : new JsonObject();
        JsonElement thought = o.get("thought");
        return new ToolCall(action.getAsString(), argObject,
                thought != null && thought.isJsonPrimitive() ? thought.getAsString() : "");
    }
}
