package es.upsa.search;

import java.util.ArrayList;
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
    @ConfigProperty(name = "rag.retriever.candidates")
    int candidates;

    /** Fragmentos que acaban en el contexto del modelo. */
    @ConfigProperty(name = "rag.retriever.max-results", defaultValue = "3")
    int maxResults;

    /**
     * Un fragmento tal y como llegó al modelo, con todo lo necesario para
     * justificar la respuesta ante quien la lee.
     *
     * Lleva el ORIGEN y la PUNTUACIÓN, y no solo el nombre del fichero, porque
     * son los dos datos que responden a la pregunta que de verdad importa:
     * "¿por qué el sistema eligió este fragmento?". Un chunk con origen "D+L"
     * lo encontraron las dos ramas por caminos independientes —el significado y
     * la literalidad— y esa coincidencia es la tesis del trabajo hecha visible.
     * Hasta ahora esta información existía, pero solo en el log del servidor.
     *
     * @param source procedencia formateada por Sources.format
     * @param origin "D" (densa), "L" (léxica) o "D+L" (ambas)
     * @param score  puntuación RRF acumulada
     * @param text   el fragmento literal que se le pasó al modelo
     */
    public record Retrieved(String source, String origin, double score, String text) {}

    /**
     * El contexto listo para el prompt Y los fragmentos que lo componen.
     *
     * Van juntos a propósito: son el mismo resultado visto de dos maneras, y
     * separarlos obligaría a recuperar dos veces o a recomponer fuera lo que
     * aquí ya está construido.
     */
    public record RetrievedContext(String text, List<Retrieved> chunks) {

        /** Sin contexto: el texto es el aviso que la regla 4 del prompt cita literalmente. */
        static RetrievedContext empty() {
            return new RetrievedContext(NO_CONTEXT, List.of());
        }

        /** ¿Se respondió sin ningún fragmento recuperado? La interfaz lo dice en voz alta. */
        public boolean isEmpty() {
            return chunks.isEmpty();
        }
    }

    /**
     * Devuelve el contexto formateado (con su procedencia) listo para inyectar
     * en el system message. Una sola entrada de log por question, con todo lo
     * necesario para explicar el resultado: tiempo, modelo de embeddings,
     * candidatos de cada rama, CONSULTA LÉXICA enviada y origin de cada
     * chunk — D (densa), L (léxica) o D+L (ambas).
     */
    public RetrievedContext retrieveContext(String question) {
        long t0 = System.nanoTime();

        List<Chunk> densos = dense.search(question);

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
            return RetrievedContext.empty();
        }

        StringBuilder context = new StringBuilder();
        StringBuilder summary = new StringBuilder(header)
                .append(" -> ").append(top.size()).append(" chunks:");
        List<Retrieved> retrieved = new ArrayList<>();
        for (RrfFusion.Result r : top) {
            summary.append(String.format("%n   [%-3s %.4f] %-40s %s",
                    r.origin(), r.score(), r.chunk().source(),
                    truncate(r.chunk().text())));
            context.append("- [").append(r.chunk().source()).append("] ")
                    .append(r.chunk().text()).append('\n');
            retrieved.add(new Retrieved(r.chunk().source(), r.origin(), r.score(), r.chunk().text()));
        }
        log.debug("{}", summary);
        return new RetrievedContext(context.toString(), List.copyOf(retrieved));
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

