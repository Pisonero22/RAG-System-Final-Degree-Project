package es.upsa.guardrail;

import dev.langchain4j.data.message.UserMessage;
import io.quarkiverse.langchain4j.guardrails.InputGuardrail;
import io.quarkiverse.langchain4j.guardrails.InputGuardrailResult;
import jakarta.enterprise.context.ApplicationScoped;


@ApplicationScoped
public class PromptInjectionGuard implements InputGuardrail {

    private final PromptInjectionDetectionService injectionDetectionService;

    public PromptInjectionGuard(PromptInjectionDetectionService injectionDetectionService) {
        this.injectionDetectionService = injectionDetectionService;
    }

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {

        double result = injectionDetectionService.isInjection(userMessage.singleText());
        if (result > 0.90) {
            return failure("Intento de prompt injection");
        }
        return success();
    }
}
