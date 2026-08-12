package es.upsa.search;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Context retrieval (explicit RAG).
 *
 * Runs both searches and fuses them. The resulting context travels in the assistant's SYSTEM
 * MESSAGE and not in the conversational memory, and that is deliberate: with a RetrievalAugmentor
 * hooked to the AI Service (verified on quarkus-langchain4j 0.26.2) the UserMessage stored in the
 * ChatMemory is the ALREADY AUGMENTED one, so the window fills up with old contexts competing
 * against each other.
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

    /** With false the system behaves exactly as it did before: dense search only. It is what
     *  makes the effect of hybrid retrieval measurable for the report. */
    @ConfigProperty(name = "rag.hybrid.enabled", defaultValue = "true")
    boolean hybridEnabled;

    /** How many candidates each branch hands to the fusion. */
    @ConfigProperty(name = "rag.retriever.candidates")
    int candidates;

    /** How many chunks end up in the model's context. */
    @ConfigProperty(name = "rag.retriever.max-results", defaultValue = "3")
    int maxResults;

    /**
     * One chunk exactly as it reached the model, with everything needed to justify the answer to
     * whoever is reading it.
     *
     * It carries the BRANCH and the SCORE, and not just the file name, because those are the two
     * facts that answer the question that actually matters: why did the system pick this chunk?
     * A chunk with origin "D+L" was found by both branches down independent paths — meaning and
     * literal match — and that agreement is the thesis of this work made visible. Until now the
     * information existed, but only in the server log.
     *
     * @param source where it came from, formatted by Sources.format
     * @param origin "D" (dense), "L" (lexical) or "D+L" (both)
     * @param score  accumulated RRF score
     * @param text   the literal fragment handed to the model
     */
    public record Retrieved(String source, String origin, double score, String text) {}

    /**
     * The context ready for the prompt AND the chunks it is made of.
     *
     * They travel together on purpose: they are the same result seen two ways, and splitting them
     * would mean retrieving twice, or rebuilding outside what is already built here.
     */
    public record RetrievedContext(String text, List<Retrieved> chunks) {

        /** No context: the text is the notice that rule 4 of the prompt quotes word for word. */
        static RetrievedContext empty() {
            return new RetrievedContext(NO_CONTEXT, List.of());
        }

        /** Answered with nothing retrieved? The interface says so out loud. */
        public boolean isEmpty() {
            return chunks.isEmpty();
        }
    }

    /**
     * The formatted context, sources included, ready to drop into the system message.
     *
     * One log entry per question, with everything needed to explain the outcome: time, embedding
     * model, candidates from each branch, the LEXICAL QUERY that was actually sent, and the
     * branch behind every chunk — D (dense), L (lexical) or D+L (both).
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

    /** Flattens and cuts so every chunk takes exactly ONE line of the log. */
    private static String truncate(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 100 ? flat : flat.substring(0, 100) + "...";
    }
    /** Flattens any whitespace so a user message can never break the one-line-per-query log. */
    private static String oneLine(String text) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }
}

