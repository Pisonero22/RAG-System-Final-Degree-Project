package es.upsa.chat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import es.upsa.ai.PromptInjectionDetectionService;
import es.upsa.ai.QueryRewriteService;
import es.upsa.ai.RagAssistant;
import dev.langchain4j.data.message.UserMessage;

import es.upsa.search.RagRetriever;
import es.upsa.memory.InMemoryChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.util.List;

@ApplicationScoped
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
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


    public String chat(String username, String message, String modelProvider){
        if (message == null || message.isBlank()) {
            return "No he recibido ningún mensaje. Escribe algo y te respondo.";
        }

        String slot = normalizeSlot(modelProvider);
        String modelTag = resolveModelTag(slot);
        String question = message.trim();


        // 0) GUARDRAIL EN LA PUERTA: antes de gastar el reescritor (un LLM) y la
        //    búsqueda vectorial en un mensaje que vamos a rechazar.
        if (isInjection(username, question)) {
            log.info("[{}] mensaje bloqueado por el detector de prompt injection", username);
            return "Mensaje bloqueado: se ha detectado un posible intento de prompt injection.";
        }


        try {
            // 1) Reescritura de la query con historial (solo para la BÚSQUEDA;
            //    el modelo del chat recibe la question original del usuario).
            String query = buildSearchQuery(username, question);

            // 2) Recuperación explícita del context (RAG).
            String context = ragRetriever.retrieveContext(query);
            // 3) Generación: el context viaja en el system message.
            long t0 = System.nanoTime();
            // El modelo recibe la question ORIGINAL, pero la búsqueda pudo hacerse con
            // una query reescrita. Si difieren, se le indica: sin esa pista, ante un
            // context que no encaja con su lectura de la question aplica la regla 4
            // (ignorar el context y responder de memoria), y se pierde la recuperación.
            String interpretation = query.equals(question) ? ""
                    : "La búsqueda del context se ha realizado interpretando la question como: \""
                    + query + "\". Si el context encaja con esa interpretación, úsalo.";

            String answer = assistant.chat(slot, username,interpretation, context, question);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.info("[{}] slot='{}' modelo='{}' respondió en {} ms",
                    username, slot, modelTag, ms);
            return answer;

        } catch (Exception e) {
            log.error("[{}] slot='{}' modelo='{}': error procesando mensaje", username, slot, modelTag, e);
            return "Ha ocurrido un error procesando tu mensaje. Inténtalo de nuevo.";
        }
    }
    private boolean isInjection(String username, String question) {
        long t0 = System.nanoTime();
        try {
            double score = detector.isInjection(question);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.debug("[{}] guardrail ({} ms) score={} para \"{}\"", username, ms, score, truncate(question));
            return score > injectionThreshold;
        } catch (Exception e) {
            log.warn("[{}] el detector de inyección falló ({} ms), se permite el mensaje: {}",
                    username, (System.nanoTime() - t0) / 1_000_000, e.getMessage());
            return false;
        }
    }

    private String resolveModelTag(String slot) {
        String propertyName = "gpt".equals(slot)
                ? "quarkus.langchain4j.openai.gpt.chat-model.model-name"
                : "quarkus.langchain4j.ollama." + slot + ".chat-model.model-id";
        return config.getOptionalValue(propertyName, String.class).orElse("desconocido");
    }

    private String normalizeSlot(String name) {
        if (name == null || name.isBlank()) {
            return "llama";
        }
        return switch (name.toLowerCase()) {
            case "openai", "gpt" -> "gpt";
            case "deepseek"      -> "deepseek";
            case "mistral"       -> "mistral";
            case "qwen"          -> "qwen";
            case "gpto"          -> "gpto";
            default              -> "llama";
        };
    }

    /**
     * Devuelve la query para la búsqueda vectorial. Si hay historial y la
     * reescritura está activa, condensa la question; ante CUALQUIER problema,
     * fallback a la question original: esta etapa es una mejora, nunca un
     * punto de fallo.
     */
    private String buildSearchQuery(String username, String question) {
        if (!rewriteEnabled) {
            return question;
        }
        String history = flattenHistory(username);
        if (history.isBlank()) {
            // Primera question: no hay nada que condensar (y nos ahorramos la llamada).
            return question;
        }
        try {
            long t0 = System.nanoTime();
            String rewritten = queryRewriter.rewrite(history, question);
            long rewriteMs = (System.nanoTime() - t0) / 1_000_000;
            if (rewritten == null || rewritten.isBlank()) {
                log.debug("[{}] reescritura vacía ({} ms), se usa la question original", username, rewriteMs);
                return question;
            }
            rewritten = rewritten.strip();
            // Debe devolver UNA query corta. Si se pone a conversar, no nos fiamos.
            if (rewritten.contains("\n") || rewritten.length() > 300) {
                log.debug("[{}] reescritura descartada ({} ms, formato inesperado)",
                        username, rewriteMs);
                return question;
            }
            // Cambios solo cosméticos: se usa la question ORIGINAL.
            if (differsOnlyInPunctuation(rewritten, question)) {
                log.debug("[{}] reescritura cosmética descartada ({} ms): \"{}\"",
                        username, rewriteMs, rewritten);
                return question;
            }
            log.debug("[{}] query rewritten ({} ms): \"{}\" -> \"{}\"",
                    username, rewriteMs, question, rewritten);

            return rewritten;
        } catch (Exception e) {
            log.warn("[{}] falló la reescritura de query, se usa la original: {}",
                    username, e.getMessage());
            return question;
        }
    }

    /**
     * Últimos mensajes de la conversación en text plano. Se filtra el
     * SystemMessage (contiene el contexto RAG del turno anterior, que aquí
     * solo metería ruido) y se recorta cada mensaje para que el prompt del
     * reescritor sea pequeño y rápido.
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

    /** Aplana y recorta un mensaje para el prompt del reescritor. */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 250 ? flat : flat.substring(0, 250) + "...";
    }
    /**
     * ¿La reescritura solo cambia signos de puntuación, tildes o mayúsculas?
     *
     * Motivo medido: añadir un punto final a "Hola" desplaza su embedding lo
     * justo para cruzar el umbral de similitud y recuperar 5 chunks de
     * ruido (patentes, Plutón, perímetros de seguridad). Observado tres veces.
     * Como es un criterio exacto, se resuelve en código y no pidiéndoselo al
     * modelo en el prompt.
     */
    static boolean differsOnlyInPunctuation(String rewritten, String original) {
        return skeleton(rewritten).equals(skeleton(original));
    }

    /** El text reducido a letras y dígitos, sin tildes ni mayúsculas. */
    static String skeleton(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")               // fuera las tildes
                .replaceAll("[^\\p{L}\\p{N}]", "")      // fuera signos y espacios
                .toLowerCase();
    }
}
