package smartparking.agents;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.FIPANames;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import smartparking.behaviours.EventReporter;
import smartparking.models.ExplainReply;
import smartparking.models.NlParseReply;
import smartparking.models.ParkingRequest;
import smartparking.services.FallbackParser;
import smartparking.services.HybridNlParser;
import smartparking.services.MistralService;
import smartparking.services.NlFidelity;
import smartparking.services.NlParseException;
import smartparking.services.ReActSession;
import smartparking.services.ToolRegistry;
import smartparking.utils.EventTypes;
import smartparking.utils.JsonUtil;
import smartparking.utils.Ontology;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Assistant LLM (stratégie C). Deux services pour les véhicules, en ontologie nl-assist :
 * <ul>
 *   <li>PARSE : une phrase libre -> demande structurée (Mistral, sinon règles du FallbackParser) ;</li>
 *   <li>EXPLAIN : une question sur une décision -> boucle ReAct avec les 5 outils du Manager (ontologie
 *       smartpark-tools). Si le LLM est absent, lent ou incohérent, réponse de secours déterministe tirée
 *       de la DecisionTrace : l'assistant n'invente jamais de fait.</li>
 * </ul>
 * Les appels HTTP se font dans un pool de threads ; leur résultat revient dans le thread de l'agent par un message
 * interne (ontologie internal), car JADE interdit de manipuler l'agent depuis un autre thread.
 *
 * Arguments : {Map&lt;String, ParkingRequest&gt; demandes d'origine (pré-analysées avant le début de la simulation,
 * pour que la latence du LLM ne fausse pas l'horloge), CountDownLatch libéré quand la pré-analyse est finie}.
 */
public class LLMAssistantAgent extends Agent {

    public static final String ASSISTANT_NAME = "assistant";

    private static final int LLM_PARALLELISM = 2;
    private static final long EXPLAIN_TIMEOUT_MS = 25_000;
    private static final String MANAGER = ParkingManagerAgent.MANAGER_NAME;

    /** Une question en cours de traitement. */
    private static final class Explain {
        final ACLMessage request;
        final String requestId;
        final ReActSession react;
        boolean done;
        boolean fallbackMode;
        String pendingTool;

        Explain(ACLMessage request, String requestId, String question) {
            this.request = request;
            this.requestId = requestId;
            this.react = new ReActSession(question, requestId);
        }
    }

    private final Queue<Runnable> inbox = new ConcurrentLinkedQueue<>();
    private final Map<String, NlParseReply> parsed = new HashMap<>();
    private final Map<String, Explain> pendingTools = new HashMap<>();
    private final Semaphore llmSlots = new Semaphore(LLM_PARALLELISM);
    private ExecutorService pool;
    private MistralService mistral;
    private HybridNlParser parser;
    private int toolSequence;
    private boolean llmErrorShown;

    @SuppressWarnings("unchecked")
    @Override
    protected void setup() {
        Object[] args = getArguments();
        Map<String, ParkingRequest> originals = args != null && args.length > 0 && args[0] instanceof Map
                ? (Map<String, ParkingRequest>) args[0] : Map.of();
        CountDownLatch ready = args != null && args.length > 1 && args[1] instanceof CountDownLatch l ? l : null;

        mistral = new MistralService();
        parser = new HybridNlParser(mistral.isAvailable() ? mistral : null, new FallbackParser());
        pool = Executors.newFixedThreadPool(LLM_PARALLELISM + 1);

        addBehaviour(new InboxBehaviour());
        addBehaviour(new NlRequestListener());
        addBehaviour(new ToolReplyListener());
        System.out.println("[assistant] prêt : " + (mistral.isAvailable()
                ? "LLM " + mistral.provider() + " (" + mistral.model() + ") avec repli sur les règles"
                : "LLM_API_KEY absente, règles seules") + ", " + originals.size() + " phrase(s) à pré-analyser");

        if (originals.isEmpty()) {
            if (ready != null) {
                ready.countDown();
            }
            return;
        }
        int[] remaining = {originals.size()};
        for (Map.Entry<String, ParkingRequest> entry : new java.util.TreeMap<>(originals).entrySet()) {
            String requestId = entry.getKey();
            ParkingRequest original = entry.getValue();
            String text = smartparking.services.NlSentenceGenerator.sentence(original);
            runParse(text, detailed -> {
                NlParseReply reply = recordParse(requestId, original, detailed);
                if (reply != null) {
                    parsed.put(requestId, reply);
                }
                if (--remaining[0] == 0) {
                    System.out.println("[assistant] pré-analyse terminée : " + parser.llmOk() + " par le LLM, "
                            + parser.fallbackUsed() + " par les règles");
                    if (ready != null) {
                        ready.countDown();
                    }
                }
            });
        }
    }

    @Override
    protected void takeDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- PARSE

    /** Parse dans le pool, puis exécute la suite dans le thread de l'agent. */
    private void runParse(String text, java.util.function.Consumer<HybridNlParser.Detailed> then) {
        pool.submit(() -> {
            HybridNlParser.Detailed result;
            try {
                llmSlots.acquire();
                try {
                    result = parser.parseDetailed(text);
                } finally {
                    llmSlots.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            post(() -> then.accept(result));
        });
    }

    /** Journalise (événements LLM_CALL / LLM_FALLBACK) et retourne la réponse à envoyer, ou null si incompréhensible. */
    private NlParseReply recordParse(String requestId, ParkingRequest original, HybridNlParser.Detailed d) {
        List<String> mismatches = original == null ? List.of() : NlFidelity.mismatches(original, d.request());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("purpose", "parse");
        details.put("source", d.source());
        details.put("latencyMs", d.latencyMs());
        details.put("mismatches", mismatches);
        if (d.llmError() != null) {
            details.put("llmError", d.llmError());
        }
        EventReporter.report(this, EventTypes.LLM_CALL, requestId, null, details);
        if (d.llmError() != null) {
            if (!llmErrorShown) {   // une seule fois : suffit pour diagnostiquer (clé invalide, réseau, quota...)
                llmErrorShown = true;
                System.out.println("[assistant] le LLM a échoué, repli sur les règles : " + d.llmError());
            }
            EventReporter.report(this, EventTypes.LLM_FALLBACK, requestId, null,
                    Map.of("purpose", "parse", "reason", d.llmError()));
        }
        if (d.request() == null) {
            return null;
        }
        return new NlParseReply(d.request(), d.source(), d.latencyMs());
    }

    private class NlRequestListener extends CyclicBehaviour {

        private final MessageTemplate template = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST),
                MessageTemplate.MatchOntology(Ontology.NL_ASSIST));

        @Override
        public void action() {
            ACLMessage message = myAgent.receive(template);
            if (message == null) {
                block();
                return;
            }
            JsonObject content;
            try {
                content = JsonParser.parseString(message.getContent()).getAsJsonObject();
            } catch (JsonParseException | IllegalStateException e) {
                reply(message, ACLMessage.NOT_UNDERSTOOD, "requête illisible");
                return;
            }
            String kind = text(content, "kind");
            String requestId = text(content, "requestId");
            if ("PARSE".equals(kind)) {
                handleParse(message, requestId, text(content, "text"));
            } else if ("EXPLAIN".equals(kind)) {
                startExplain(message, requestId, text(content, "question"));
            } else {
                reply(message, ACLMessage.NOT_UNDERSTOOD, "type de requête inconnu");
            }
        }
    }

    private void handleParse(ACLMessage message, String requestId, String text) {
        NlParseReply cached = requestId == null ? null : parsed.get(requestId);
        if (cached != null) {
            reply(message, ACLMessage.INFORM, JsonUtil.toJson(cached));
            return;
        }
        if (text == null || text.isBlank()) {
            reply(message, ACLMessage.FAILURE, "phrase vide");
            return;
        }
        runParse(text, detailed -> {
            NlParseReply r = recordParse(requestId, null, detailed);
            if (r == null) {
                reply(message, ACLMessage.FAILURE, "demande incompréhensible");
            } else {
                reply(message, ACLMessage.INFORM, JsonUtil.toJson(r));
            }
        });
    }

    // -------------------------------------------------------------- EXPLAIN

    private void startExplain(ACLMessage message, String requestId, String question) {
        if (requestId == null || question == null || question.isBlank()) {
            reply(message, ACLMessage.FAILURE, "requestId et question sont obligatoires");
            return;
        }
        Explain session = new Explain(message, requestId, question);
        addBehaviour(new WakerBehaviour(this, EXPLAIN_TIMEOUT_MS) {
            @Override
            protected void onWake() {
                if (!session.done) {
                    fallback(session, "délai dépassé");
                }
            }
        });
        if (!mistral.isAvailable()) {
            fallback(session, "pas de clé API");
            return;
        }
        askLlm(session);
    }

    /** Un appel au LLM dans le pool ; la suite est exécutée dans le thread de l'agent. */
    private void askLlm(Explain session) {
        String prompt = session.react.userPrompt();
        pool.submit(() -> {
            long start = System.nanoTime();
            String output = null;
            String error = null;
            try {
                llmSlots.acquire();
                try {
                    output = mistral.complete(ReActSession.SYSTEM_PROMPT, prompt, true);
                } finally {
                    llmSlots.release();
                }
            } catch (NlParseException e) {
                error = e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            String out = output;
            String err = error;
            post(() -> onLlmOutput(session, out, err, ms));
        });
    }

    private void onLlmOutput(Explain session, String output, String error, long latencyMs) {
        if (session.done) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("purpose", "react");
        details.put("latencyMs", latencyMs);
        details.put("ok", error == null);
        EventReporter.report(this, EventTypes.LLM_CALL, session.requestId, null, details);
        if (error != null) {
            fallback(session, "LLM : " + error);
            return;
        }
        ReActSession.Step step = session.react.onLlmOutput(output);
        if (step instanceof ReActSession.Final done) {
            session.done = true;
            answerExplain(session, done.text(), "REACT", session.react.toolCalls());
            return;
        }
        if (session.react.exhausted()) {
            fallback(session, "boucle ReAct sans réponse exploitable");
            return;
        }
        if (step instanceof ReActSession.ToolCall call) {
            // liste blanche vérifiée ici ; le Manager valide ensuite les arguments avec son état
            if (!ToolRegistry.isAllowed(call.tool())) {
                String problem = "outil non autorisé : " + call.tool();
                EventReporter.report(this, EventTypes.TOOL_REFUSED, session.requestId, null,
                        Map.of("tool", String.valueOf(call.tool()), "reason", problem));
                session.react.onObservation(call.tool(), JsonUtil.toJson(Map.of("error", "refusé : " + problem)));
                askLlm(session);
                return;
            }
            callTool(session, call.tool(), call.args());
            return;
        }
        askLlm(session);   // sortie invalide : l'historique contient déjà le rappel du format attendu
    }

    private void callTool(Explain session, String tool, JsonObject args) {
        String conversation = "tool-" + (++toolSequence);
        session.pendingTool = tool;
        pendingTools.put(conversation, session);
        JsonObject content = new JsonObject();
        content.addProperty("tool", tool);
        content.add("args", args);
        ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
        request.setProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST);
        request.setOntology(Ontology.TOOLS);
        request.setLanguage(Ontology.LANGUAGE);
        request.setConversationId(conversation);
        request.addReceiver(new AID(MANAGER, AID.ISLOCALNAME));
        request.setContent(content.toString());
        send(request);
    }

    /** Réponse du Manager à un outil : observation pour le LLM, ou texte de secours si on est en mode repli. */
    private class ToolReplyListener extends CyclicBehaviour {

        private final MessageTemplate template = MessageTemplate.MatchOntology(Ontology.TOOLS);

        @Override
        public void action() {
            ACLMessage message = myAgent.receive(template);
            if (message == null) {
                block();
                return;
            }
            if (message.getPerformative() == ACLMessage.REQUEST) {
                return;   // pas pour nous
            }
            Explain session = pendingTools.remove(message.getConversationId());
            if (session == null || session.done) {
                return;
            }
            String observation = message.getContent();
            if (session.fallbackMode) {
                session.done = true;
                answerExplain(session, ToolRegistry.explanationFromDecision(observation), "TEMPLATE", 1);
                return;
            }
            session.react.onObservation(session.pendingTool, observation);
            askLlm(session);
        }
    }

    /**
     * Remet l'explication au véhicule, la journalise ici (le véhicule peut être déjà reparti) et signale
     * au Monitor qu'elle est terminée, pour qu'il n'arrête pas la simulation avant.
     */
    private void answerExplain(Explain session, String text, String source, int steps) {
        reply(session.request, ACLMessage.INFORM, JsonUtil.toJson(new ExplainReply(text, source, steps)));
        System.out.println("[assistant] explication pour " + session.request.getSender().getLocalName()
                + " (" + source + ", " + session.requestId + ") : " + text);
        EventReporter.report(this, EventTypes.EXPLAIN_DONE, session.requestId, null,
                Map.of("source", source, "steps", steps));
    }

    /** Réponse de secours : synthèse de la décision, lue par l'outil get_decision. */
    private void fallback(Explain session, String why) {
        if (session.done || session.fallbackMode) {
            return;
        }
        session.fallbackMode = true;
        EventReporter.report(this, EventTypes.LLM_FALLBACK, session.requestId, null,
                Map.of("purpose", "react", "reason", why));
        JsonObject args = new JsonObject();
        args.addProperty("requestId", session.requestId);
        callTool(session, ToolRegistry.GET_DECISION, args);
    }

    // -------------------------------------------------------------- utilitaires

    /** Messages internes : exécute dans le thread de l'agent ce que les threads du pool ont préparé. */
    private class InboxBehaviour extends CyclicBehaviour {

        private final MessageTemplate template = MessageTemplate.MatchOntology(Ontology.INTERNAL);

        @Override
        public void action() {
            ACLMessage message = myAgent.receive(template);
            if (message == null) {
                block();
                return;
            }
            Runnable task;
            while ((task = inbox.poll()) != null) {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    System.err.println("[assistant] erreur interne : " + e);
                }
            }
        }
    }

    /** Appelable depuis n'importe quel thread : déposer la tâche puis réveiller l'agent. */
    private void post(Runnable task) {
        inbox.add(task);
        ACLMessage wake = new ACLMessage(ACLMessage.INFORM);
        wake.setOntology(Ontology.INTERNAL);
        wake.setContent("wake");
        postMessage(wake);
    }

    private void reply(ACLMessage to, int performative, String content) {
        ACLMessage reply = to.createReply();
        reply.setPerformative(performative);
        reply.setOntology(Ontology.NL_ASSIST);
        reply.setLanguage(Ontology.LANGUAGE);
        reply.setContent(content);
        send(reply);
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }
}
