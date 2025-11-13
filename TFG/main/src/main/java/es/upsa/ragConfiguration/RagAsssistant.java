package es.upsa.ragConfiguration;


import dev.langchain4j.service.SystemMessage;
import es.upsa.guardrail.PromptInjectionGuard;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.guardrails.InputGuardrails;
import jakarta.enterprise.context.ApplicationScoped;

@RegisterAiService
@ApplicationScoped
public interface RagAsssistant {

    @SystemMessage("""
    Eres un asistente experto en generación aumentada por recuperación (RAG). El sistema te proporcionará fragmentos de información extraídos de la base de embeddings.
       1. NUNCA respondas con “No tengo información” o expresiones similares.
       2. Integra siempre los fragmentos vigentes en tu respuesta.
       3. Limita tu salida a un máximo de 40 palabras
    """)
    //@InputGuardrails(PromptInjectionGuard.class)
    String augmentedChat(String userMessage);
}
