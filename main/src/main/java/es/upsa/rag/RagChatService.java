package es.upsa.rag;

import es.upsa.ragconfiguration.RagAssistant;

import io.quarkiverse.langchain4j.runtime.aiservice.GuardrailException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);

    @Inject
    RagAssistant assistant;

    public String chat(String username, String message, String modelProvider){
        try {

            return assistant.chat(normalizarModelo(modelProvider), username, message);
        } catch (GuardrailException e) {
            log.info("Mensaje bloqueado por guardrail para '{}': {}", username, e.getMessage());
            return "Mensaje bloqueado: se ha detectado un posible intento de prompt injection.";
        } catch (Exception e) {
            log.error("Error procesando mensaje de '{}'", username, e);
            return "Ha ocurrido un error procesando tu mensaje. Inténtalo de nuevo.";
        }
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
