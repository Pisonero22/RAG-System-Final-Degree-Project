package es.upsa.guardrail;

import dev.langchain4j.data.message.UserMessage;
import io.quarkiverse.langchain4j.guardrails.InputGuardrail;
import io.quarkiverse.langchain4j.guardrails.InputGuardrailResult;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@ApplicationScoped
public class PromptInjectionGuard implements InputGuardrail {


    private static final Logger log = LoggerFactory.getLogger(PromptInjectionGuard.class);
    private static final double UMBRAL = 0.89;

    private final PromptInjectionDetectionService detector;

    public PromptInjectionGuard(PromptInjectionDetectionService detector) {
        this.detector = detector;
    }

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        try {
            double score = detector.isInjection(userMessage.singleText());
            log.debug("Score de inyección para \"{}\": {}", userMessage.singleText(), score);
            if (score > UMBRAL) {
                return failure("Intento de prompt injection (score " + score + ")");
            }
            return success();
        } catch (Exception e) {
            // El detector es a su vez un LLM y puede devolver algo no parseable a double.
            // Decisión fail-open: se registra y se deja pasar el mensaje (bloquear chats
            // legítimos por un fallo del clasificador es peor que dejar pasar un dudoso;
            // el system prompt del asistente sigue actuando como segunda barrera).
            log.warn("El detector de inyección falló, se permite el mensaje: {}", e.getMessage());
            return success();
        }
    }
}
