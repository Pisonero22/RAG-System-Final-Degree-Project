package es.upsa.security;

import dev.langchain4j.data.message.UserMessage;
import es.upsa.guardrail.PromptInjectionDetectionService;
import io.quarkiverse.langchain4j.guardrails.InputGuardrail;
import io.quarkiverse.langchain4j.guardrails.InputGuardrailResult;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.quarkiverse.langchain4j.guardrails.InputGuardrailResult.success;
import static io.quarkus.logging.Log.fatal;

@ApplicationScoped
public class PromptInjectionGuard  implements InputGuardrail{

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionGuard.class);
    private static final double UMBRAL = 0.90;

    private final PromptInjectionDetectionService detector;

    public PromptInjectionGuard(PromptInjectionDetectionService detector) {
        this.detector = detector;
    }

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        try {
            double score = detector.isInjection(userMessage.singleText());
            log.debug("Score de inyección: {}", score);
            if (score > UMBRAL) {
                return fatal("Posible intento de prompt injection");
            }
            return success();
        } catch (Exception e) {
            // El detector es a su vez un LLM y puede devolver algo no parseable a double.
            // fail-open: registramos y dejamos pasar. (fail-closed sería devolver fatal();
            // elige y justifícalo en la memoria.)
            log.warn("El detector de inyección falló, se permite el mensaje: {}", e.getMessage());
            return success();
        }
    }

}
