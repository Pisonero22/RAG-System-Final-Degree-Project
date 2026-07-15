package es.upsa.configuration.deepseek;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import es.upsa.configuration.ModelProvider;
import es.upsa.providers.llms.DeepSeekProvider;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;

@ApplicationScoped
@DeepSeekProvider
public class DeepSeekModelConfiguration implements ModelProvider {

    @Override
    public ChatLanguageModel getChatLanguageModel() {
        return OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("deepseek-r1:7b")
                .httpClientBuilder(new JdkHttpClientBuilder())
                .timeout(Duration.ofMillis(60000))
                .build();
    }

    @Override
    public EmbeddingModel getEmbeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl("http://localhost:11434/")
                .modelName("nomic-embed-text")
                .httpClientBuilder(new JdkHttpClientBuilder())
                .build();
    }
}
