package es.upsa.eval;

import es.upsa.search.Chunk;
import es.upsa.search.DenseSearch;
import es.upsa.search.LexicalSearch;
import es.upsa.search.RrfFusion;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EVALUATION, not a test.
 *
 * Measures how well the retrieval stage finds the right document, over a golden set of questions
 * whose correct source is known in advance. Every question is run through the three strategies —
 * dense only, lexical only and the RRF fusion — inside a SINGLE execution, so all three are
 * measured against the same index at the same instant: no configuration is switched, no
 * reindexing happens in between, and the corpus cannot drift.
 *
 * Two standard information-retrieval metrics:
 *   hit@3 — is the expected source among the three fragments handed to the model?
 *   MRR   — mean of 1/position of the expected source. Unlike hit@3 it also rewards moving a
 *           correct fragment from position 4 to position 1, which is what the fusion does.
 *
 * Deliberately NOT hermetic: it queries the development Redis index and requires the corpus to
 * be ingested beforehand. That is why it lives in its own package and carries the "benchmark"
 * tag: the unit tests in es.upsa.search, es.upsa.chat and es.upsa.ingestion are the regression
 * guards and need nothing; this class is a measuring instrument and is run on demand.
 *
 * Run with: ./mvnw test -Pbenchmark
 * Output:   main/target/retrieval-benchmark.md
 */
@QuarkusTest
@Tag("benchmark")
class RetrievalBenchmarkTest {

    /** Positions that count as a hit. Mirrors rag.retriever.max-results. */
    private static final int TOP_K = 3;

    /** Candidates each branch contributes to the fusion. Mirrors rag.retriever.candidates. */
    private static final int CANDIDATES = 10;

    private static final String GOLDEN_SET = "/eval/golden-set.csv";

    @Inject
    DenseSearch dense;

    @Inject
    LexicalSearch lexical;

    @Inject
    RrfFusion fusion;

    /** One row of the golden set. */
    private record GoldenCase(String id, String question, String expectedSource, String kind) {}

    /** What the three strategies did with one question. Rank is 1-based; 0 means not found. */
    private record Outcome(GoldenCase testCase, int denseRank, int lexicalRank, int hybridRank) {}

    /** hit@k and MRR for one strategy over a set of outcomes. */
    private record Metric(String name, double hitRate, double mrr, int total) {}

    @Test
    void compareTheThreeRetrievalStrategies() throws IOException {
        List<GoldenCase> goldenSet = loadGoldenSet();
        assertTrue(goldenSet.size() >= 10, "the golden set is too small to mean anything");

        List<Outcome> outcomes = new ArrayList<>();
        for (GoldenCase testCase : goldenSet) {
            List<Chunk> denseChunks = dense.search(testCase.question(), CANDIDATES);
            List<Chunk> lexicalChunks = lexical.search(testCase.question(), CANDIDATES).chunks();
            List<Chunk> fusedChunks = fusion.fuse(denseChunks, lexicalChunks, CANDIDATES)
                    .stream()
                    .map(RrfFusion.Result::chunk)
                    .toList();

            outcomes.add(new Outcome(testCase,
                    rankOf(denseChunks, testCase.expectedSource()),
                    rankOf(lexicalChunks, testCase.expectedSource()),
                    rankOf(fusedChunks, testCase.expectedSource())));
        }

        String report = buildReport(outcomes);
        Path output = Path.of("target", "retrieval-benchmark.md");
        Files.writeString(output, report, StandardCharsets.UTF_8);
        System.out.println(System.lineSeparator() + report);
        System.out.println("report written to " + output.toAbsolutePath());

        Metric denseOnly = metricOf("dense only", outcomes, Outcome::denseRank);
        Metric hybrid = metricOf("hybrid (RRF)", outcomes, Outcome::hybridRank);

        // The claim under test: fusing must never rank worse than the dense branch alone.
        assertTrue(hybrid.mrr() >= denseOnly.mrr(),
                String.format("the fusion lost to the dense branch: MRR %.3f < %.3f",
                        hybrid.mrr(), denseOnly.mrr()));

        // A floor, deliberately set well below the current value so that KNOWN limitations do
        // not turn the build red. It only catches a change that breaks retrieval wholesale.
        assertTrue(hybrid.hitRate() >= 0.60,
                String.format("hit@%d collapsed to %.2f", TOP_K, hybrid.hitRate()));
    }

    /**
     * A greeting must retrieve nothing. This is the control case for rag.retriever.min-score:
     * if lowering the threshold to recover cross-lingual matches starts returning fragments
     * here, the threshold has become too permissive.
     */
    @Test
    void aGreetingRetrievesNothing() {
        assertTrue(dense.search("Hola", CANDIDATES).isEmpty(),
                "the dense threshold has become too permissive");
        assertTrue(lexical.search("Hola", CANDIDATES).chunks().isEmpty(),
                "the lexical branch is matching a bare greeting");
    }

    // ------------------------------------------------------------------ metrics

    /** 1-based position of the expected source in the list, or 0 when it is absent. */
    private static int rankOf(List<Chunk> chunks, String expectedSource) {
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).source().contains(expectedSource)) {
                return i + 1;
            }
        }
        return 0;
    }

    private static Metric metricOf(String name, List<Outcome> outcomes, ToIntFunction<Outcome> rank) {
        int hits = 0;
        double reciprocalRankSum = 0.0;
        for (Outcome outcome : outcomes) {
            int position = rank.applyAsInt(outcome);
            if (position > 0) {
                reciprocalRankSum += 1.0 / position;
                if (position <= TOP_K) {
                    hits++;
                }
            }
        }
        int total = outcomes.size();
        return new Metric(name, (double) hits / total, reciprocalRankSum / total, total);
    }

    // ------------------------------------------------------------------ report

    private String buildReport(List<Outcome> outcomes) {
        StringBuilder out = new StringBuilder();

        out.append("# Retrieval benchmark\n\n")
                .append("Run: ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append("  ·  questions: ").append(outcomes.size())
                .append("  ·  candidates per branch: ").append(CANDIDATES)
                .append("  ·  hit@").append(TOP_K).append("\n\n");

        out.append("## Overall\n\n");
        out.append(metricTable(List.of(
                metricOf("dense only", outcomes, Outcome::denseRank),
                metricOf("lexical only", outcomes, Outcome::lexicalRank),
                metricOf("hybrid (RRF)", outcomes, Outcome::hybridRank))));

        out.append("\n## By question type\n\n");
        out.append("| kind | n | dense hit@").append(TOP_K).append(" | lexical hit@").append(TOP_K)
                .append(" | hybrid hit@").append(TOP_K).append(" | dense MRR | lexical MRR | hybrid MRR |\n")
                .append("|---|---|---|---|---|---|---|---|\n");

        Map<String, List<Outcome>> byKind = new LinkedHashMap<>();
        for (Outcome outcome : outcomes) {
            byKind.computeIfAbsent(outcome.testCase().kind(), k -> new ArrayList<>()).add(outcome);
        }
        for (Map.Entry<String, List<Outcome>> entry : byKind.entrySet()) {
            List<Outcome> group = entry.getValue();
            Metric d = metricOf("d", group, Outcome::denseRank);
            Metric l = metricOf("l", group, Outcome::lexicalRank);
            Metric h = metricOf("h", group, Outcome::hybridRank);
            out.append(String.format("| %s | %d | %.2f | %.2f | %.2f | %.3f | %.3f | %.3f |%n",
                    entry.getKey(), group.size(),
                    d.hitRate(), l.hitRate(), h.hitRate(),
                    d.mrr(), l.mrr(), h.mrr()));
        }

        out.append("\n## Question by question\n\n");
        out.append("| # | kind | question | dense | lexical | hybrid |\n")
               .append("|------|---|---|----|---|---|\n");
        for (Outcome outcome : outcomes) {
            out.append(String.format("| %s | %s | %s | %s | %s | %s |%n",
                    outcome.testCase().id(), outcome.testCase().kind(),
                    outcome.testCase().question(),
                    position(outcome.denseRank()),
                    position(outcome.lexicalRank()),
                    position(outcome.hybridRank())));
        }
        out.append("\n`#n` is the position of the expected source; `—` means it was not retrieved.\n");
        return out.toString();
    }

    private static String metricTable(List<Metric> metrics) {
        StringBuilder out = new StringBuilder()
                .append("| strategy | hit@").append(TOP_K).append(" | MRR |\n")
                .append("|---|---|---|\n");
        for (Metric metric : metrics) {
            out.append(String.format("| %s | %.2f | %.3f |%n",
                    metric.name(), metric.hitRate(), metric.mrr()));
        }
        return out.toString();
    }

    private static String position(int rank) {
        return rank == 0 ? "—" : "#" + rank;
    }

    // ------------------------------------------------------------------ golden set

    private List<GoldenCase> loadGoldenSet() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(GOLDEN_SET)) {
            if (stream == null) {
                throw new IllegalStateException("golden set not on the classpath: " + GOLDEN_SET);
            }
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                List<GoldenCase> cases = new ArrayList<>();
                boolean headerSeen = false;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    if (!headerSeen) {
                        headerSeen = true;      // the first non-comment line is the header
                        continue;
                    }
                    String[] fields = line.split(";", -1);
                    cases.add(new GoldenCase(fields[0].trim(), fields[1].trim(),
                            fields[2].trim(), fields[3].trim()));
                }
                return cases;
            }
        }
    }
}