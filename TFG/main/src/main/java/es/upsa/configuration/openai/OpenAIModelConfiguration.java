package es.upsa.configuration.openai;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import es.upsa.configuration.ModelProvider;
import es.upsa.providers.llms.OpenAIProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

@ApplicationScoped
@OpenAIProvider
public class OpenAIModelConfiguration implements ModelProvider {

    @Inject
    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    String apiKey;

    @Override
    public ChatLanguageModel getChatLanguageModel() {
        return OpenAiChatModel.builder()
                .modelName("gpt-4")
                .apiKey(apiKey)
                .temperature(0.7)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Override
    public EmbeddingModel getEmbeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName("text-embedding-ada-002")
                .dimensions(1536)
                .build();
    }
}
