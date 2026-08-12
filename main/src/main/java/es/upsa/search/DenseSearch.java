package es.upsa.search;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.quarkiverse.langchain4j.redis.RedisEmbeddingStore;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;


/**
 * Semantic search: turns the question into a vector with bge-m3 and returns the closest chunks
 * by cosine similarity.
 *
 * It finds by MEANING, so it answers paraphrases and synonyms that share no word with the text.
 * The price is that squeezing everything into 1024 dimensions blurs the literal detail — "Clase
 * IV" and "Clase III" end up almost in the same spot — and that is exactly what the lexical
 * search is there to cover.
 */
@ApplicationScoped
public class DenseSearch {

    private static final Logger log = LoggerFactory.getLogger(DenseSearch.class);

    @Inject
    RedisEmbeddingStore embeddingStore;
    @Inject
    EmbeddingModel embeddingModel;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.embedding-model.model-id")
    String embeddingModelId;
    @ConfigProperty(name = "quarkus.langchain4j.redis.dimension")
    long dimension;
    @ConfigProperty(name = "rag.retriever.candidates")
    int candidates;
    /**
     * Calibrated on real logs (score = (1 + cosine) / 2 with bge-m3): the clearly relevant ones
     * sit at 0.79-0.86 and the grey zone at 0.72-0.78. Fixed at 0.75 because at 0.72 a greeting
     * like "Hola" pulled in five chunks of noise — patents, Pluto, security perimeters — while no
     * real question lost anything: its hits came in with 6, 4 and 3 candidates.
     */
    @ConfigProperty(name = "rag.retriever.min-score", defaultValue = "0.75")
    double minScore;

    private EmbeddingStoreContentRetriever retriever;

    @PostConstruct
    void init() {
        this.retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .maxResults(candidates)          // the real cut-off is the caller's decision
                .minScore(minScore)
                .build();
        log.info("Dense search ready: embeddings='{}' ({} dims), minScore={}", embeddingModelId, dimension, minScore);
    }

    /** Chunks ordered by similarity, at most rag.retriever.candidates of them. */
    public List<Chunk> search(String question) {
        if (question == null || question.isBlank()) {
            return List.of();       // Query.from("") throws
        }
        return retriever.retrieve(Query.from(question)).stream()
                .map(c -> {
                    Object score = c.metadata().get(ContentMetadata.SCORE);   // the cosine, 0..1
                    log.debug("  dense score={} {}", score,
                            Sources.format(c.textSegment().metadata().toMap()));
                    return new Chunk(c.textSegment().text(),
                            Sources.format(c.textSegment().metadata().toMap()));
                })
                .toList();
    }

    public String embeddingModelId() {
        return embeddingModelId;
    }

}
