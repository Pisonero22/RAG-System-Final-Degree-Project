package es.upsa.rag;

import es.upsa.ragconfiguration.RagAssistant;

import es.upsa.ragconfiguration.RagRetriever;
import io.quarkiverse.langchain4j.runtime.aiservice.GuardrailException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    @Inject
    RagAssistant assistant;
    @Inject
    RagRetriever ragRetriever;
    @Inject
    Config config;

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
            // 1) Recuperación explícita del contexto (RAG). El retriever loguea
            //    su propia línea con embeddings, chunks, scores y ms de búsqueda.
            String contexto = ragRetriever.buscarContexto(pregunta);

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
            default              -> "llama";
        };
    }

}
