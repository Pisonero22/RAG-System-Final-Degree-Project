package es.upsa.search;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recuperación de contexto (RAG explícito).
 *
 * Orquesta las dos búsquedas y las fusiona; el contexto resultante viaja en el
 * SYSTEM MESSAGE del asistente y no en la memoria conversacional. Motivo
 * (verificado en quarkus-langchain4j 0.26.2): al enganchar un RetrievalAugmentor
 * al AI Service, el UserMessage que se guarda en la ChatMemory es el YA
 * AUMENTADO, de modo que la ventana se llena de contextos antiguos que compiten
 * entre sí.
 */
@ApplicationScoped
public class RagRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagRetriever.class);

    public static final String NO_CONTEXT = "(no se ha recuperado ningún documento relevante)";

    @Inject
    DenseSearch dense;
    @Inject
    LexicalSearch lexical;
    @Inject
    RrfFusion fusion;

    /** Con false, el sistema se comporta exactamente como antes (solo búsqueda
     *  densa). Permite medir el efecto de la hibridación en la memoria del TFG. */
    @ConfigProperty(name = "rag.hybrid.enabled", defaultValue = "true")
    boolean hybridEnabled;

    /** Candidatos que aporta cada búsqueda a la fusión. */
    @ConfigProperty(name = "rag.retriever.candidates", defaultValue = "10")
    int candidates;

    /** Fragmentos que acaban en el contexto del modelo. */
    @ConfigProperty(name = "rag.retriever.max-results", defaultValue = "3")
    int maxResults;

    /**
     * Devuelve el contexto formateado (con su procedencia) listo para inyectar
     * en el system message. Una sola entrada de log por question, con todo lo
     * necesario para explicar el resultado: tiempo, modelo de embeddings,
     * candidatos de cada rama, CONSULTA LÉXICA enviada y origin de cada
     * chunk — D (densa), L (léxica) o D+L (ambas).
     */
    public String retrieveContext(String question) {
        long t0 = System.nanoTime();

        List<Chunk> densos = dense.search(question, candidates);

        LexicalSearch.LexicalResult lexicalResult = hybridEnabled
                ? lexical.search(question, candidates)
                : LexicalSearch.LexicalResult.empty("(híbrida desactivada)");
        List<Chunk> lexicalChunks = lexicalResult.chunks();

        List<RrfFusion.Result> top = fusion.fuse(densos, lexicalChunks, maxResults);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        String header = String.format("RAG (%d ms | emb='%s' | %dD+%dL | lex=\"%s\") \"%s\"",
                ms, dense.embeddingModelId(), densos.size(), lexicalChunks.size(),
                oneLine(lexicalResult.query()),oneLine(question));

        if (top.isEmpty()) {
            log.debug("{} -> 0 chunks (sin contexto relevante)", header);
            return NO_CONTEXT;
        }

        StringBuilder context = new StringBuilder();
        StringBuilder summary = new StringBuilder(header)
                .append(" -> ").append(top.size()).append(" chunks:");

        for (RrfFusion.Result r : top) {
            summary.append(String.format("%n   [%-3s %.4f] %-40s %s",
                    r.origin(), r.score(), r.chunk().source(),
                    truncate(r.chunk().text())));
            context.append("- [").append(r.chunk().source()).append("] ")
                    .append(r.chunk().text()).append('\n');
        }
        log.debug("{}", summary);
        return context.toString();
    }

    /** Aplana y trunca para que cada chunk ocupe UNA línea del log. */
    private static String truncate(String text) {
        String plano = text.replaceAll("\\s+", " ").trim();
        return plano.length() <= 100 ? plano : plano.substring(0, 100) + "...";
    }
    /** Flattens any whitespace so a user message can never break the one-line-per-query log. */
    private static String oneLine(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }
}

