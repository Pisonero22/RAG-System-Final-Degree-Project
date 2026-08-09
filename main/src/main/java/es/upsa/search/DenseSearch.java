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
 * Búsqueda semántica: convierte la pregunta en un vector con bge-m3 y devuelve
 * los chunks más cercanos por coseno.
 *
 * Encuentra por SIGNIFICADO: responde a paráfrasis y sinónimos aunque no
 * compartan ninguna palabra con el text. A cambio, la compresión a 1024
 * dimensiones difumina la literalidad ("Clase IV" y "Clase III" quedan casi en
 * el mismo punto), que es justo lo que cubre la búsqueda léxica.
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
     * Calibrado con logs reales (score = (1+coseno)/2 con bge-m3): los relevantes
     * claros están en 0,79-0,86 y la zona gris en 0,72-0,78. Se fijó en 0,75
     * porque a 0,72 saludos como "Hola" recuperaban cinco chunks de ruido
     * (patentes, Plutón, perímetros de seguridad) mientras que ninguna pregunta
     * real perdía chunks: los aciertos entraban con 6, 4 y 3 candidatos.
     */
    @ConfigProperty(name = "rag.retriever.min-score", defaultValue = "0.75")
    double minScore;

    private EmbeddingStoreContentRetriever retriever;

    @PostConstruct
    void init() {
        this.retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .maxResults(candidates)          // el recorte real lo decide quien llama
                .minScore(minScore)
                .build();
        log.info("Búsqueda densa lista: embeddings='{}' ({} dims), minScore={}",
                embeddingModelId, dimension, minScore);
    }

    /** Fragmentos ordenados por similitud, como máximo 'limit'. */
    public List<Chunk> search(String question) {
        if (question == null || question.isBlank()) {
            return List.of();       // Query.from("") lanza excepción
        }
        return retriever.retrieve(Query.from(question)).stream()
                .map(c -> {
                    Object score = c.metadata().get(ContentMetadata.SCORE);   // <-- el coseno
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
