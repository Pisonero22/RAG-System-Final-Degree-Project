package es.upsa.chat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import es.upsa.ai.ModelSlot;
import es.upsa.ai.PromptInjectionDetectionService;
import es.upsa.ai.QueryRewriteService;
import es.upsa.ai.RagAssistant;
import dev.langchain4j.data.message.UserMessage;

import es.upsa.search.RagRetriever;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

@ApplicationScoped
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    /** Three exchanges. Enough for a follow-up to make sense, short enough to stay cheap. */
    private static final int HISTORY_MESSAGES = 6;

    @Inject
    RagAssistant assistant;
    @Inject
    RagRetriever ragRetriever;
    @Inject
    Config config;
    @Inject
    QueryRewriteService queryRewriter;
    @Inject
    InMemoryChatMemoryStore memoryStore;

    @Inject
    PromptInjectionDetectionService detector;

    @ConfigProperty(name = "guardrail.threshold", defaultValue = "0.89")
    double injectionThreshold;

    @ConfigProperty(name = "rag.query-rewrite.enabled", defaultValue = "true")
    boolean rewriteEnabled;

    private static final Pattern FOLLOW_UP_START =
            Pattern.compile("^\\s*(y|and|¿y|pero|but|then|entonces)\\b", Pattern.CASE_INSENSITIVE);
    /** A code: two or more letters glued to two or more digits, like "SKU-2041". */
    private static final Pattern IDENTIFIER = Pattern.compile("\\p{L}{2,}[-_]?\\d{2,}");

    public record Answer(String text, String model, long millis,
                         List<RagRetriever.Retrieved> sources) {

        /** An answer that never reached a model: empty message, block, or error. */
        static Answer plain(String text) {
            return new Answer(text, null, 0L, List.of());
        }
    }


    /**
     * The conversational pipeline: guardrail, query rewriting, retrieval, generation.
     *
     * Every stage after the guardrail degrades instead of failing. A rewriter that breaks, a Redis
     * that is down or a lexical branch that returns nothing all end with an answer — a worse one —
     * and never with an error. The only stage that stops a message is the guardrail, and that is
     * why it runs first.
     */
    public Answer chat(String username, String message, String modelProvider){
        if (message == null || message.isBlank()) {
            return Answer.plain("I did not get any message. Write something and I will answer.");
        }

        ModelSlot slot =  ModelSlot.from(modelProvider);
        String modelTag = config.getOptionalValue(slot.modelProperty(), String.class).orElse("desconocido");
        String question = message.trim();


        // 0) Guardrail at the door, before spending the rewriter (an LLM call) and the vector
        //    search on a message we are going to reject anyway.
        if (isInjection(username, question)) {
            log.info("[{}] message blocked by the prompt injection detector", username);
            return Answer.plain("Message blocked: this looks like a prompt injection attempt.");
        }


        try {
            // 1) Rewrite the query using the history — for the SEARCH only. The chat model gets
            //    the user's original question.
            String query = buildSearchQuery(username, question);

            // 2) Explicit context retrieval (RAG).
            RagRetriever.RetrievedContext context = ragRetriever.retrieveContext(query);
            // 3) Generation: the context travels in the system message.
            long t0 = System.nanoTime();
            // The model gets the ORIGINAL question, but the search may have run on a rewritten
            // one. When they differ we say so: without that hint, a context that does not match
            // its reading of the question triggers rule 4 — ignore the context, answer from
            // memory — and the whole retrieval is thrown away.
            String interpretation = query.equals(question) ? ""
                    : "La búsqueda del context se ha realizado interpretando la pregunta como: \""
                    + query + "\". Si el context encaja con esa interpretación, úsalo.";

            String answer = assistant.chat(slot.slot(), username,interpretation, context.text(), question);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.info("[{}] slot='{}' model='{}' answered in {} ms", username, slot, modelTag, ms);
            return new Answer(answer,modelTag,ms,context.chunks());

        } catch (Exception e) {
            log.error("[{}] slot='{}' model='{}': failed to process the message", username, slot, modelTag, e);
            return Answer.plain("Something went wrong while processing your message. Try again.");
        }
    }
    private boolean isInjection(String username, String question) {
        long t0 = System.nanoTime();
        try {
            double score = detector.isInjection(question);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.debug("[{}] guardrail ({} ms) score={} for \"{}\"", username, ms, score, truncate(question));;
            return score > injectionThreshold;
        } catch (Exception e) {
            log.warn("[{}] injection detector failed ({} ms), letting the message through: {}",
                    username, (System.nanoTime() - t0) / 1_000_000, e.getMessage());
            return false;
        }
    }

    /**
     * The query for the vector search. With history, and with rewriting enabled, it condenses the
     * question; on ANY problem it falls back to the original one. This stage is an improvement,
     * never a point of failure.
     */
    private String buildSearchQuery(String username, String question) {
        if (!rewriteEnabled) {
            return question;
        }
        String history = flattenHistory(username);
        if (history.isBlank()) {
            // First question of the conversation: nothing to condense, and we save the call.
            return question;
        }
        if (!looksElliptical(question)) {
            log.debug("[{}] self-contained message, rewriter skipped", username);
            return question;
        }
        try {
            long t0 = System.nanoTime();
            String rewritten = queryRewriter.rewrite(history, question);
            long rewriteMs = (System.nanoTime() - t0) / 1_000_000;
            if (rewritten == null || rewritten.isBlank()) {
                log.debug("[{}] empty rewrite ({} ms), keeping the original question", username, rewriteMs);
                return question;
            }
            rewritten = rewritten.strip();
            // It has to come back as ONE short query. If it starts chatting, we do not trust it.
            if (rewritten.contains("\n") || rewritten.length() > 300) {
                log.debug("[{}] rewrite discarded ({} ms, unexpected format)", username, rewriteMs);
                return question;
            }
            // Cosmetic change only: keep the ORIGINAL question.
            if (differsOnlyInPunctuation(rewritten, question)) {
                log.debug("[{}] cosmetic rewrite discarded ({} ms): \"{}\"", username, rewriteMs, rewritten);
                return question;
            }

            log.debug("[{}] query rewritten ({} ms): \"{}\" -> \"{}\"",
                    username, rewriteMs, question, rewritten);

            return rewritten;
        } catch (Exception e) {
            log.warn("[{}] query rewrite failed, keeping the original: {}", username, e.getMessage());
            return question;
        }
    }

    /**
     * The last few messages as plain text. The SystemMessage is filtered out — it carries the RAG
     * context of the previous turn, which is nothing but noise here — and every message is cut
     * short so the rewriter's prompt stays small and fast.
     */
    private String flattenHistory(String username) {
        List<ChatMessage> messages = memoryStore.getMessages(username);
        if (messages.isEmpty()) {
            return "";
        }
        int from = Math.max(0, messages.size() - HISTORY_MESSAGES);
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages.subList(from, messages.size())) {
            if (m instanceof UserMessage um) {
                sb.append("Usuario: ").append(truncate(um.singleText())).append('\n');
            } else if (m instanceof AiMessage am) {
                sb.append("Asistente: ").append(truncate(am.text())).append('\n');
            }
        }
        return sb.toString();
    }

    /** Flattens and cuts a message down for the rewriter's prompt. */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 250 ? flat : flat.substring(0, 250) + "...";
    }
    /**
     * Does the rewrite only change punctuation, accents or case?
     *
     * Measured: adding a full stop to "Hola" moves its embedding just enough to cross the
     * similarity threshold and pull in 5 chunks of noise — patents, Pluto, security perimeters.
     * Seen three times. The criterion is exact, so it is settled in code instead of being asked
     * of the model in the prompt.
     */
    static boolean differsOnlyInPunctuation(String rewritten, String original) {
        return skeleton(rewritten).equals(skeleton(original));
    }

    /** The text reduced to letters and digits: no accents, no case. */
    static String skeleton(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")               // accents out
                .replaceAll("[^\\p{L}\\p{N}]", "")      // punctuation and spaces out
                .toLowerCase();
    }
    /**
     * Is this message worth handing to the rewriter? Short ones, and ones opening with "y",
     * "and", "but"..., usually lean on the previous turn.
     *
     * A message carrying an identifier never does, whatever its length. Measured: "SKU-2041" was
     * being treated as elliptical and rewritten into a question about its price — an intent
     * imported from the previous turn — which took retrieval from 9D+1L with the right row first
     * to 1D+0L with the wrong one.
     */
    static boolean looksElliptical(String message) {
        if (IDENTIFIER.matcher(message).find()) {
            return false;
        }
        return message.trim().split("\\s+").length < 6
                || FOLLOW_UP_START.matcher(message).find();
    }
}
