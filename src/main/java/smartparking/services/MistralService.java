package smartparking.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import smartparking.models.ParkingRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.BiFunction;

/**
 * Client minimal d'une API « chat/completions » compatible OpenAI (Mistral par défaut, Groq, Gemini...).
 * Configuration par variables d'environnement (jamais dans le code ni dans les fichiers de config) :
 * <ul>
 *   <li>LLM_API_KEY (à défaut MISTRAL_API_KEY) : la clé ;</li>
 *   <li>LLM_BASE_URL : par défaut https://api.mistral.ai/v1 (Groq : https://api.groq.com/openai/v1) ;</li>
 *   <li>LLM_MODEL (à défaut MISTRAL_MODEL) : par défaut mistral-small-latest.</li>
 * </ul>
 */
public final class MistralService implements NlParser, LlmClient {

    public static final String DEFAULT_BASE_URL = "https://api.mistral.ai/v1";
    public static final String ENDPOINT = DEFAULT_BASE_URL + "/chat/completions";
    public static final String DEFAULT_MODEL = "mistral-small-latest";

    static final String PARSE_SYSTEM_PROMPT = """
            Tu convertis une demande de stationnement en français ou anglais en un unique objet JSON, sans texte autour.
            Schéma exact :
            {"duration": <heures, nombre>, "maxBudget": <dinars DT, nombre>,
             "vehicleType": "STANDARD"|"ELECTRIC"|"PMR", "requiresCharging": <bool>, "requiresPmr": <bool>,
             "priority": "NORMAL"|"PMR"|"URGENT", "preferredLocation": "ANY"|"NEAR_EXIT"}
            Règles : voiture électrique ou recharge => ELECTRIC et requiresCharging=true ;
            handicap/PMR => PMR, requiresPmr=true, priority PMR ; "urgent" => priority URGENT ;
            "près de la sortie" => NEAR_EXIT. Si le budget n'est pas donné, mets 10000.
            Ne réponds qu'avec le JSON.""";

    /** Transport HTTP injectable (tests) : (corps JSON, clé) -> corps de la réponse. */
    @FunctionalInterface
    public interface Transport {
        String post(String url, String apiKey, String jsonBody, Duration timeout) throws IOException;
    }

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final Transport transport;

    public MistralService() {
        this(endpointFrom(env("LLM_BASE_URL", DEFAULT_BASE_URL)),
                env("LLM_API_KEY", env("MISTRAL_API_KEY", "")),
                env("LLM_MODEL", env("MISTRAL_MODEL", DEFAULT_MODEL)),
                Duration.ofSeconds(8), MistralService::httpTransport);
    }

    public MistralService(String apiKey, String model, Duration timeout, Transport transport) {
        this(ENDPOINT, apiKey, model, timeout, transport);
    }

    public MistralService(String endpoint, String apiKey, String model, Duration timeout, Transport transport) {
        this.endpoint = endpoint;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.timeout = timeout;
        this.transport = transport;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    static String endpointFrom(String baseUrl) {
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return b.endsWith("/chat/completions") ? b : b + "/chat/completions";
    }

    /** Nom court du fournisseur (hôte de l'URL), pour les messages. */
    public String provider() {
        try {
            return URI.create(endpoint).getHost();
        } catch (RuntimeException e) {
            return "LLM";
        }
    }

    @Override
    public boolean isAvailable() {
        return !apiKey.isEmpty();
    }

    public String model() {
        return model;
    }

    @Override
    public ParkingRequest parse(String text) throws NlParseException {
        String answer = complete(PARSE_SYSTEM_PROMPT, text, true);
        return JsonValidator.validate(answer);
    }

    /** Appel générique (réutilisé par l'agent ReAct) : renvoie le texte de la première réponse. */
    @Override
    public String complete(String system, String user, boolean jsonMode) throws NlParseException {
        if (!isAvailable()) {
            throw new NlParseException("LLM_API_KEY absente");
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.0);
        if (jsonMode) {
            JsonObject fmt = new JsonObject();
            fmt.addProperty("type", "json_object");
            body.add("response_format", fmt);
        }
        JsonArray messages = new JsonArray();
        messages.add(message("system", system));
        messages.add(message("user", user));
        body.add("messages", messages);

        String response;
        try {
            response = transport.post(endpoint, apiKey, body.toString(), timeout);
        } catch (IOException e) {
            throw new NlParseException("appel LLM échoué : " + e.getMessage(), e);
        }
        return extractContent(response);
    }

    static String extractContent(String response) throws NlParseException {
        try {
            JsonObject root = JsonParser.parseString(response).getAsJsonObject();
            JsonElement content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content");
            if (content == null || content.isJsonNull() || content.getAsString().isBlank()) {
                throw new NlParseException("réponse LLM vide");
            }
            return content.getAsString();
        } catch (NlParseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NlParseException("réponse LLM inattendue", e);
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    /** Début du corps d'une erreur de l'API (message de Mistral), pour le diagnostic. */
    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
    }

    /** HTTP avec jusqu'à 3 essais si l'API limite le débit (429) ou est momentanément indisponible (5xx). */
    private static String httpTransport(String url, String key, String body, Duration timeout)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + key)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();
                if (code / 100 == 2) {
                    return res.body();
                }
                last = new IOException("HTTP " + code + " " + snippet(res.body()));
                if (code != 429 && code / 100 != 5) {
                    throw last;            // erreur définitive (clé invalide, requête incorrecte...)
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("appel interrompu", e);
            }
            try {
                Thread.sleep(1200L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("appel interrompu", e);
            }
        }
        throw last;
    }
}
