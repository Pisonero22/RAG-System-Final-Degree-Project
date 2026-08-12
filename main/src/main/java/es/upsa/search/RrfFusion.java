package es.upsa.search;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.*;

/**
 * Reciprocal Rank Fusion: merges several ranked lists into one. Every chunk adds
 * weight / (k + position) for each list it shows up in.
 *
 * RANKS and not scores, because the two scales are not comparable — cosine sits between 0.7 and
 * 0.9, BM25 has no ceiling — and normalising them creates artefacts: a search where everything
 * scored badly would be stretched until it looked as good as an excellent one.
 *
 * With k=60, the conventional value, the gaps between consecutive ranks are small, so showing up
 * in BOTH lists is worth more than being first in only one. That is exactly the behaviour we are
 * after: a chunk found by meaning AND by literal match is almost always the right one.
 *
 * No external dependencies. It is a pure function, and it can be tested without Redis or any
 * model running.
 */
@ApplicationScoped
public class RrfFusion {


    @ConfigProperty(name = "rag.fusion.k", defaultValue = "60")
    int k;

    /** Weight of the dense list in the RRF sum. See lexicalWeight below: both stay at 1.0. */
    @ConfigProperty(name = "rag.fusion.dense-weight", defaultValue = "1.0")
    double denseWeight;
    /**
     * Both branches weigh the same. A lower lexical weight (0.7) was tried, aimed at the
     * accidental matches of conversational questions, and it wiped the lexical branch out
     * completely: with k=60, 0.7/61 falls below 1.0/70, so the WORST dense result beat the BEST
     * lexical one and no lexical chunk ever reached the final three.
     *
     * The conversational noise was fixed where it belongs — in the query, with conjunctive
     * semantics and a stop-word filter — and not by penalising a whole branch.
     */
    @ConfigProperty(name = "rag.fusion.lexical-weight", defaultValue = "1.0")
    double lexicalWeight;

    /** A fused chunk, with the branch that found it ("D", "L" or "D+L") and its score. */
    public record Result(Chunk chunk, String origin, double score) {}

    public List<Result> fuse(List<Chunk> denseChunks, List<Chunk> lexicalChunks, int limit) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Chunk> byTexts = new LinkedHashMap<>();
        Map<String, String> origin = new LinkedHashMap<>();

        accumulate(denseChunks, denseWeight, "D", scores, byTexts, origin);
        accumulate(lexicalChunks, lexicalWeight, "L", scores, byTexts, origin);

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new Result(byTexts.get(e.getKey()), origin.get(e.getKey()), e.getValue()))
                .toList();
    }

    private void accumulate(List<Chunk> chunks, double weight, String label,
                            Map<String, Double> scores, Map<String, Chunk> byText,
                            Map<String, String> origins) {
        Set<String> alreadyScored = new HashSet<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            if (!alreadyScored.add(chunk.text())) {
                continue;                       // already scored in this branch, at a better rank
            }
            scores.merge(chunk.text(), weight / (k + i + 1), Double::sum);
            byText.putIfAbsent(chunk.text(), chunk);
            origins.merge(chunk.text(), label, (ya, nuevo) -> ya + "+" + nuevo);
        }
    }

}
