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
        long t0 = System.nanoTime();
        try {
            double score = detector.isInjection(userMessage.singleText());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.debug("Guardrail ({} ms) score={} para \"{}\"", ms, score, userMessage.singleText());
            if (score > UMBRAL) {
                return failure("Intento de prompt injection (score " + score + ")");
            }
            return success();
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.warn("El detector de inyección falló ({} ms), se permite el mensaje: {}", ms, e.getMessage());
            return success();
        }
    }
}
