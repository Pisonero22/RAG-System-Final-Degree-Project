package es.upsa.configuration.mistral;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import es.upsa.configuration.ModelProvider;
import es.upsa.providers.llms.MistralProvider;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

@ApplicationScoped
@MistralProvider
public class MistralModelConfiguration implements ModelProvider {

    @ConfigProperty(name = "ollama.base-url")
    String baseURL;

    @ConfigProperty(name = "mistral.chat-model-id")
    String chatModelId;

    @Override
    public ChatLanguageModel getChatLanguageModel() {
        return OllamaChatModel.builder()
                .baseUrl(baseURL)
                .modelName(chatModelId)
                .httpClientBuilder(new JdkHttpClientBuilder())
                .timeout(Duration.ofMillis(60000))
                .build();
    }

    @Override
    public EmbeddingModel getEmbeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl("http://localhost:11434/")
                .modelName("bge-large")
                .httpClientBuilder(new JdkHttpClientBuilder())
                .build();
    }
}
