package es.upsa.rag;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import es.upsa.ragconfiguration.QueryRewriteService;
import es.upsa.ragconfiguration.RagAssistant;
import dev.langchain4j.data.message.UserMessage;

import es.upsa.ragconfiguration.RagRetriever;
import es.upsa.store.RagChatMemoryStore;
import io.quarkiverse.langchain4j.runtime.aiservice.GuardrailException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@ApplicationScoped
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);
    private static final int MENSAJES_PARA_REESCRITURA = 6;

    @Inject
    RagAssistant assistant;
    @Inject
    RagRetriever ragRetriever;
    @Inject
    Config config;
    @Inject
    QueryRewriteService queryRewriter;
    @Inject
    RagChatMemoryStore memoryStore;

    @ConfigProperty(name = "rag.query-rewrite.enabled", defaultValue = "true")
    boolean rewriteEnabled;


    public String chat(String username, String message, String modelProvider){
        // Defensa en profundidad: el WebSocket ya filtra vacíos, pero este servicio
        // también puede llamarse desde otros puntos (tests, REST futuro...).
        if (message == null || message.isBlank()) {
            return "No he recibido ningún mensaje. Escribe algo y te respondo.";
        }

        String slot = normalizarModelo(modelProvider);
        String modeloReal = tagReal(slot);
        String pregunta = message.trim();
        try {
            // 1) Reescritura de la consulta con historial (solo para la BÚSQUEDA;
            //    el modelo del chat recibe la pregunta original del usuario).
            String consulta = consultaParaBusqueda(username, pregunta);

            // 2) Recuperación explícita del contexto (RAG).
            String contexto = ragRetriever.buscarContexto(consulta);
            // 2) Generación: el contexto viaja en el system message.
            long t0 = System.nanoTime();
            String respuesta = assistant.chat(slot, username, contexto, pregunta);
            long ms = (System.nanoTime() - t0) / 1_000_000;

            log.info("[{}] slot='{}' modelo='{}' generó la respuesta en {} ms",
                    username, slot, modeloReal, ms);
            return respuesta;

        } catch (GuardrailException e) {
            log.info("[{}] slot='{}' modelo='{}': mensaje bloqueado por guardrail: {}",
                    username, slot, modeloReal, e.getMessage());
            return "Mensaje bloqueado: se ha detectado un posible intento de prompt injection.";
        } catch (Exception e) {
            log.error("[{}] slot='{}' modelo='{}': error procesando mensaje",
                    username, slot, modeloReal, e);
            return "Ha ocurrido un error procesando tu mensaje. Inténtalo de nuevo.";
        }
    }

    private String tagReal(String slot) {
        String propiedad = "gpt".equals(slot)
                ? "quarkus.langchain4j.openai.gpt.chat-model.model-name"
                : "quarkus.langchain4j.ollama." + slot + ".chat-model.model-id";
        return config.getOptionalValue(propiedad, String.class).orElse("desconocido");
    }

    private String normalizarModelo(String name) {
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
     * Devuelve la consulta para la búsqueda vectorial. Si hay historial y la
     * reescritura está activa, condensa la pregunta; ante CUALQUIER problema,
     * fallback a la pregunta original: esta etapa es una mejora, nunca un
     * punto de fallo.
     */
    private String consultaParaBusqueda(String username, String pregunta) {
        if (!rewriteEnabled) {
            return pregunta;
        }
        String historial = historialPlano(username);
        if (historial.isBlank()) {
            // Primera pregunta: no hay nada que condensar (y nos ahorramos la llamada).
            return pregunta;
        }
        try {
            String reescrita = queryRewriter.reescribir(historial, pregunta);
            if (reescrita == null || reescrita.isBlank()) {
                return pregunta;
            }
            reescrita = reescrita.strip();
            // Debe devolver UNA consulta corta. Si se pone a conversar, no nos fiamos.
            if (reescrita.contains("\n") || reescrita.length() > 300) {
                log.debug("[{}] reescritura descartada (formato inesperado)", username);
                return pregunta;
            }
            if (!reescrita.equalsIgnoreCase(pregunta)) {
                log.debug("[{}] consulta reescrita para búsqueda: \"{}\" -> \"{}\"",
                        username, pregunta, reescrita);
            }
            return reescrita;
        } catch (Exception e) {
            log.warn("[{}] falló la reescritura de consulta, se usa la original: {}",
                    username, e.getMessage());
            return pregunta;
        }
    }

    /**
     * Últimos mensajes de la conversación en texto plano. Se filtra el
     * SystemMessage (contiene el contexto RAG del turno anterior, que aquí
     * solo metería ruido) y se recorta cada mensaje para que el prompt del
     * reescritor sea pequeño y rápido.
     */
    private String historialPlano(String username) {
        List<ChatMessage> mensajes = memoryStore.getMessages(username);
        if (mensajes.isEmpty()) {
            return "";
        }
        int desde = Math.max(0, mensajes.size() - MENSAJES_PARA_REESCRITURA);
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : mensajes.subList(desde, mensajes.size())) {
            if (m instanceof UserMessage um) {
                sb.append("Usuario: ").append(resumen(um.singleText())).append('\n');
            } else if (m instanceof AiMessage am) {
                sb.append("Asistente: ").append(resumen(am.text())).append('\n');
            }
        }
        return sb.toString();
    }

    /** Aplana y recorta un mensaje para el prompt del reescritor. */
    private static String resumen(String texto) {
        if (texto == null) {
            return "";
        }
        String plano = texto.replaceAll("\\s+", " ").trim();
        return plano.length() <= 250 ? plano : plano.substring(0, 250) + "...";
    }

}
