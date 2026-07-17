package es.upsa.ragconfiguration;

import java.util.List;
import io.quarkiverse.langchain4j.redis.RedisEmbeddingStore;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class RagRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagRetriever.class);

    @Inject
    RedisEmbeddingStore embeddingStore;
    @Inject
    EmbeddingModel embeddingModel;
    public RetrievalAugmentor getRetrievalAugmentor() {
        EmbeddingStoreContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                //Dos embeddings se eligen
                .maxResults(2)
                //El grado de similitud minimo
                .minScore(0.78)
                .build();
        return DefaultRetrievalAugmentor.builder()
                .contentRetriever(contentRetriever)
                .contentInjector(new ContentInjector() {
                    @Override
                    public UserMessage inject(List<Content> list, UserMessage userMessage) {
                        if (list.isEmpty()) {
                            // Sin resultados: no fabricamos un contexto falso.
                            // El system prompt ya indica qué hacer cuando no hay contexto.
                            return userMessage;
                        }
                        StringBuilder prompt = new StringBuilder(userMessage.singleText());
                        prompt.append("\n\nContexto recuperado de la base de conocimiento:\n");
                        list.forEach(content ->
                                prompt.append("- ").append(content.textSegment().text()).append('\n'));
                        log.debug("Prompt final:\n{}", prompt);
                        return new UserMessage(prompt.toString());
                    }
                })
                .build();
    }

}
