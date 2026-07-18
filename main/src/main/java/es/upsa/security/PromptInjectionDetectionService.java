package es.upsa.security;

import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class,
retrievalAugmentor = RegisterAiService.NoRetrievalAugmentorSupplier.class)
@ApplicationScoped
public interface PromptInjectionDetectionService {
}
