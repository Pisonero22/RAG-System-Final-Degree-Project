package es.upsa.busqueda;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
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
 * los fragmentos más cercanos por coseno.
 *
 * Encuentra por SIGNIFICADO: responde a paráfrasis y sinónimos aunque no
 * compartan ninguna palabra con el texto. A cambio, la compresión a 1024
 * dimensiones difumina la literalidad ("Clase IV" y "Clase III" quedan casi en
 * el mismo punto), que es justo lo que cubre la búsqueda léxica.
 */
@ApplicationScoped
public class BusquedaDensa {

    private static final Logger log = LoggerFactory.getLogger(BusquedaDensa.class);

    @Inject
    RedisEmbeddingStore embeddingStore;
    @Inject
    EmbeddingModel embeddingModel;

    @ConfigProperty(name = "quarkus.langchain4j.ollama.embedding-model.model-id")
    String modeloEmbeddings;
    @ConfigProperty(name = "quarkus.langchain4j.redis.dimension")
    long dimension;

    /**
     * Calibrado con logs reales (score = (1+coseno)/2 con bge-m3): los relevantes
     * claros están en 0,79-0,86 y la zona gris en 0,72-0,78. Se fijó en 0,75
     * porque a 0,72 saludos como "Hola" recuperaban cinco fragmentos de ruido
     * (patentes, Plutón, perímetros de seguridad) mientras que ninguna pregunta
     * real perdía fragmentos: los aciertos entraban con 6, 4 y 3 candidatos.
     */
    @ConfigProperty(name = "rag.retriever.min-score", defaultValue = "0.75")
    double minScore;

    private EmbeddingStoreContentRetriever retriever;

    @PostConstruct
    void init() {
        this.retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .maxResults(100)          // el recorte real lo decide quien llama
                .minScore(minScore)
                .build();
        log.info("Búsqueda densa lista: embeddings='{}' ({} dims), minScore={}",
                modeloEmbeddings, dimension, minScore);
    }

    /** Fragmentos ordenados por similitud, como máximo 'limite'. */
    public List<Fragmento> buscar(String pregunta, int limite) {
        if (pregunta == null || pregunta.isBlank()) {
            return List.of();       // Query.from("") lanza excepción
        }
        List<Content> contenidos = retriever.retrieve(Query.from(pregunta));
        return contenidos.stream()
                .limit(limite)
                .map(c -> new Fragmento(
                        c.textSegment().text(),
                        Fuentes.formatear(c.textSegment().metadata().toMap())))
                .toList();
    }

    public String modeloEmbeddings() {
        return modeloEmbeddings;
    }

}
