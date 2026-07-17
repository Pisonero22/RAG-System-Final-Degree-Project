package es.upsa.rag;

import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServices;
import es.upsa.configuration.ModelProvider;
import es.upsa.providers.llms.DeepSeekProvider;
import es.upsa.providers.llms.MistralProvider;
import es.upsa.providers.llms.OllamaProvider;
import es.upsa.providers.llms.OpenAIProvider;
import es.upsa.ragconfiguration.RagAssistant;
import es.upsa.ragconfiguration.RagRetriever;
import es.upsa.store.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class RagChatService {


    @Inject
    RagRetriever ragRetriever;

    @Inject
    ChatMemoryStore chatMemoryStore;

    @Inject
    @OllamaProvider
    ModelProvider ollamaProvider;
    @Inject
    @OpenAIProvider
    ModelProvider openAIProvider;
    @Inject
    @DeepSeekProvider
    ModelProvider deepSeekProvider;
    @Inject
    @MistralProvider
    ModelProvider mistralProvider;

    public String getIngestResponseWithRag(String username, String message, String modelProvider) {
        RetrievalAugmentor retrievalAugmentor = ragRetriever.getRetrievalAugmentor();
        return AiServices.builder(RagAssistant.class)
                .chatLanguageModel(getProviderByName(modelProvider).getChatLanguageModel())
                .chatMemory(chatMemoryStore.getOrCreate(username))
                .retrievalAugmentor(retrievalAugmentor)
                .build()
                .augmentedChat(message);
    }


    public ModelProvider getProviderByName(String name) {

        if (name == null || name.isBlank()) {
            return ollamaProvider;
        }
        return switch (name.toLowerCase()) {
            case "openai" -> openAIProvider;
            case "ollama" -> ollamaProvider;
            case "deepseek" -> deepSeekProvider;
            case "mistral" -> mistralProvider;
            default -> ollamaProvider;
        };
    }

}
