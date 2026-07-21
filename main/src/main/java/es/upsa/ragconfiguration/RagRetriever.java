package es.upsa.ragconfiguration;

import java.util.List;
import java.util.Map;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.quarkiverse.langchain4j.redis.RedisEmbeddingStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recuperación EXPLÍCITA de contexto (RAG "manual").
 *
 * Ya no implementa Supplier<RetrievalAugmentor>. Motivo (verificado en el código
 * de quarkus-langchain4j 0.26.2, AiServiceMethodImplementationSupport): cuando el
 * augmentor se engancha al AI Service, el UserMessage que se guarda en la
 * ChatMemory es el YA AUMENTADO (pregunta + todo el contexto inyectado). Con una
 * ventana de 8 mensajes, la memoria se llena de contextos antiguos que compiten
 * entre sí y confunden al modelo (por eso mezclaba UPSA con UCD).
 *
 * Recuperando aquí el contexto y pasándolo al system message vía {context},
 * en memoria solo quedan las preguntas limpias del usuario.
 */
@ApplicationScoped
public class RagRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagRetriever.class);

    public static final String SIN_CONTEXTO = "(no se ha recuperado ningún documento relevante)";

    private static final int MAX_RESULTS = 3;
    // Calibrado con logs reales (score = (1+coseno)/2 con bge-m3):
    //   relevantes claros 0.79-0.86; zona gris 0.72-0.78 (mezcla relevantes e
    //   irrelevantes). El umbral es red de seguridad; la zona gris la resuelven
    //   las reglas del system prompt y el modelo.
    private static final double MIN_SCORE = 0.72;

    private final EmbeddingStoreContentRetriever retriever;
    private final String embeddingTag;

    @Inject
    public RagRetriever(RedisEmbeddingStore embeddingStore,
                        EmbeddingModel embeddingModel,
                        @ConfigProperty(name = "quarkus.langchain4j.ollama.embedding-model.model-id") String embeddingTag,
                        @ConfigProperty(name = "quarkus.langchain4j.redis.dimension") long dimension) {
        this.embeddingTag = embeddingTag;
        this.retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .maxResults(MAX_RESULTS)
                .minScore(MIN_SCORE)
                .build();
        // Banner de arranque: deja constancia en el log de la configuración RAG activa.
        log.info("RAG listo: embeddings='{}' ({} dims), maxResults={}, minScore={}",
                embeddingTag, dimension, MAX_RESULTS, MIN_SCORE);
    }

    /**
     * Devuelve el contexto formateado (con su fuente) listo para inyectar
     * en el system message del asistente.
     *
     * Log: UNA sola entrada por pregunta con el modelo de embeddings usado,
     * los ms de búsqueda y todos los chunks agrupados (una línea por chunk).
     */
    public String buscarContexto(String pregunta) {
        long t0 = System.nanoTime();
        List<Content> contents = retriever.retrieve(Query.from(pregunta));
        long ms = (System.nanoTime() - t0) / 1_000_000;

        if (contents.isEmpty()) {
            log.debug("RAG ({} ms | emb='{}') \"{}\" -> 0 chunks (sin contexto relevante)",
                    ms, embeddingTag, pregunta);
            return SIN_CONTEXTO;
        }

        StringBuilder contexto = new StringBuilder();
        StringBuilder resumenLog = new StringBuilder();
        resumenLog.append("RAG (").append(ms).append(" ms | emb='").append(embeddingTag)
                .append("') \"").append(pregunta)
                .append("\" -> ").append(contents.size()).append(" chunks:");

        for (Content content : contents) {
            String fuente = fuente(content.textSegment().metadata());
            Object scoreObj = content.metadata().get(ContentMetadata.SCORE);
            double score = (scoreObj instanceof Number n) ? n.doubleValue() : -1;

            resumenLog.append(String.format("%n   [%.3f] %-40s %s",
                    score, fuente, resumen(content.textSegment().text())));

            contexto.append("- [").append(fuente).append("] ")
                    .append(content.textSegment().text()).append('\n');
        }
        log.debug("{}", resumenLog);
        return contexto.toString();
    }

    /**
     * OJO: Metadata.getString(...) lanza excepción si el valor no es String
     * ("page" y "fila" vuelven de Redis como números) -> toMap() + String.valueOf.
     */
    private static String fuente(Metadata md) {
        Map<String, Object> m = md.toMap();
        if (m.containsKey("file")) {
            return limpiarNombre(m.get("file")) + " (pág. " + num(m.get("page")) + ")";
        }
        if (m.containsKey("file_name")) {
            return limpiarNombre(m.get("file_name"));
        }
        if (m.containsKey("nombre")) {
            return limpiarNombre(m.get("nombre")) + " (fila " + num(m.get("fila")) + ")";
        }
        return "fuente desconocida";
    }

    /** Quita el prefijo UUID que añade la subida de archivos: "bd49ab9c-..._Productos.csv" -> "Productos.csv". */
    private static String limpiarNombre(Object v) {
        return String.valueOf(v).replaceFirst("^[0-9a-fA-F-]{36}_", "");
    }

    /** Los números vuelven de Redis como double (16.0): se muestran sin decimales (16). */
    private static String num(Object v) {
        return (v instanceof Number n) ? String.valueOf(n.longValue()) : String.valueOf(v);
    }

    /** Aplana saltos de línea y espacios repetidos y trunca, para que cada chunk ocupe UNA línea del log. */
    private static String resumen(String texto) {
        String plano = texto.replaceAll("\\s+", " ").trim();
        return plano.length() <= 100 ? plano : plano.substring(0, 100) + "...";
    }
}