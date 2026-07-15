package es.upsa.configuration;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;

public interface ModelProvider {
    ChatLanguageModel getChatLanguageModel();
    EmbeddingModel getEmbeddingModel();
}
