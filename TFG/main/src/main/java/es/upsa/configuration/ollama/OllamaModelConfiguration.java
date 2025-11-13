package es.upsa.configuration.ollama;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import es.upsa.configuration.ModelProvider;
import es.upsa.providers.llms.OllamaProvider;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;

@ApplicationScoped
@OllamaProvider
public class OllamaModelConfiguration implements ModelProvider {


    @Override
    public ChatLanguageModel getChatLanguageModel() {
        return OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("llama3.2")
                .httpClientBuilder(new JdkHttpClientBuilder())
                .timeout(Duration.ofMillis(60000))
                .temperature(0.6)               // mas creatividad
                .topP(0.8)                      // deja que explore más
                .topK(20)                       // más opciones para elegir
                .build();

    }

    @Override
    public EmbeddingModel getEmbeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl("http://localhost:11434/")
                .modelName("mxbai-embed-large")
                .httpClientBuilder(new JdkHttpClientBuilder())
                .build();
    }
}




