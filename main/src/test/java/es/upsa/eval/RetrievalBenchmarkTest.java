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
 * Writes:   main/target/retrieval-benchmark.md    tables, ready to paste into the README
 *           main/target/retrieval-benchmark.csv   one row per question, opens in a spreadsheet
 */
@QuarkusTest
@Tag("benchmark")
class RetrievalBenchmarkTest {

    /** Positions that count as a hit. Mirrors rag.retriever.max-results. */
    private static final int TOP_K = 3;

    /** Candidates each branch contributes to the fusion. Mirrors rag.retriever.candidates. */
    private static final int CANDIDATES = 10;

    private static final String GOLDEN_SET = "/eval/golden-set.csv";

    /** Semicolon, not comma: questions contain commas and Excel opens it straight away. */
    private static final String CSV_SEPARATOR = ";";

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

        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        Path markdown = write("retrieval-benchmark.md", buildMarkdown(outcomes, timestamp));
        Path csv = write("retrieval-benchmark.csv", buildCsv(outcomes));
        System.out.print(buildConsoleSummary(outcomes, timestamp, markdown, csv));

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

    private static Metric metricOf(String name, List<Outcome> outcomes,
                                   ToIntFunction<Outcome> rank) {
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

    private static Map<String, List<Outcome>> groupByKind(List<Outcome> outcomes) {
        Map<String, List<Outcome>> byKind = new LinkedHashMap<>();
        for (Outcome outcome : outcomes) {
            byKind.computeIfAbsent(outcome.testCase().kind(), k -> new ArrayList<>()).add(outcome);
        }
        return byKind;
    }

    // ------------------------------------------------------------------ console

    /**
     * A compact, aligned summary for the terminal. The full question-by-question table goes to
     * the files instead, so the console stays readable. ASCII only, to keep the columns aligned
     * whatever the terminal encoding is.
     */
    private String buildConsoleSummary(List<Outcome> outcomes, String timestamp,
                                       Path markdown, Path csv) {
        String rule = "=".repeat(74);
        StringBuilder out = new StringBuilder("\n").append(rule).append('\n');
        out.append(String.format("  RETRIEVAL BENCHMARK  |  %s  |  %d questions  |  hit@%d%n",
                timestamp, outcomes.size(), TOP_K));
        out.append(rule).append("\n\n");

        out.append("  OVERALL\n");
        out.append(String.format("  %-16s %8s %8s%n", "strategy", "hit@" + TOP_K, "MRR"));
        out.append("  ").append("-".repeat(33)).append('\n');
        for (Metric metric : List.of(
                metricOf("dense only", outcomes, Outcome::denseRank),
                metricOf("lexical only", outcomes, Outcome::lexicalRank),
                metricOf("hybrid (RRF)", outcomes, Outcome::hybridRank))) {
            out.append(String.format("  %-16s %8.2f %8.3f%n",
                    metric.name(), metric.hitRate(), metric.mrr()));
        }

        out.append("\n  BY QUESTION TYPE\n");
        out.append(String.format("  %-26s %3s %8s %8s %8s%n",
                "kind", "n", "dense", "lexical", "hybrid"));
        out.append("  ").append("-".repeat(56)).append('\n');
        for (Map.Entry<String, List<Outcome>> entry : groupByKind(outcomes).entrySet()) {
            List<Outcome> group = entry.getValue();
            out.append(String.format("  %-26s %3d %8.2f %8.2f %8.2f%n",
                    entry.getKey(), group.size(),
                    metricOf("d", group, Outcome::denseRank).hitRate(),
                    metricOf("l", group, Outcome::lexicalRank).hitRate(),
                    metricOf("h", group, Outcome::hybridRank).hitRate()));
        }

        List<Outcome> missed = outcomes.stream()
                .filter(outcome -> outcome.hybridRank() == 0 || outcome.hybridRank() > TOP_K)
                .toList();
        out.append(String.format("%n  NOT FOUND BY THE HYBRID (%d of %d)%n",
                missed.size(), outcomes.size()));
        out.append("  ").append("-".repeat(70)).append('\n');
        if (missed.isEmpty()) {
            out.append("  (none)\n");
        } else {
            for (Outcome outcome : missed) {
                out.append(String.format("  %-5s %-26s %s%n",
                        outcome.testCase().id(), outcome.testCase().kind(),
                        truncate(outcome.testCase().question(), 36)));
            }
        }

        out.append("\n  FILES\n");
        out.append("  ").append("-".repeat(70)).append('\n');
        out.append("  ").append(markdown.toAbsolutePath()).append('\n');
        out.append("  ").append(csv.toAbsolutePath()).append('\n');
        return out.append(rule).append('\n').toString();
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 3) + "...";
    }

    // ------------------------------------------------------------------ files

    private static Path write(String fileName, String content) throws IOException {
        Path output = Path.of("target", fileName);
        Files.createDirectories(output.getParent());
        Files.writeString(output, content, StandardCharsets.UTF_8);
        return output;
    }

    /** Markdown with the three tables, ready to paste into the README. */
    private String buildMarkdown(List<Outcome> outcomes, String timestamp) {
        StringBuilder out = new StringBuilder("# Retrieval benchmark\n\n");
        out.append("Run: ").append(timestamp)
                .append(" | questions: ").append(outcomes.size())
                .append(" | candidates per branch: ").append(CANDIDATES)
                .append(" | hit@").append(TOP_K).append("\n\n");

        out.append("## Overall\n\n")
                .append("| strategy | hit@").append(TOP_K).append(" | MRR |\n")
                .append("|---|---|---|\n");
        for (Metric metric : List.of(
                metricOf("dense only", outcomes, Outcome::denseRank),
                metricOf("lexical only", outcomes, Outcome::lexicalRank),
                metricOf("hybrid (RRF)", outcomes, Outcome::hybridRank))) {
            out.append(String.format("| %s | %.2f | %.3f |%n",
                    metric.name(), metric.hitRate(), metric.mrr()));
        }

        out.append("\n## By question type\n\n")
                .append("| kind | n | dense hit@").append(TOP_K)
                .append(" | lexical hit@").append(TOP_K)
                .append(" | hybrid hit@").append(TOP_K)
                .append(" | dense MRR | lexical MRR | hybrid MRR |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, List<Outcome>> entry : groupByKind(outcomes).entrySet()) {
            List<Outcome> group = entry.getValue();
            Metric d = metricOf("d", group, Outcome::denseRank);
            Metric l = metricOf("l", group, Outcome::lexicalRank);
            Metric h = metricOf("h", group, Outcome::hybridRank);
            out.append(String.format("| %s | %d | %.2f | %.2f | %.2f | %.3f | %.3f | %.3f |%n",
                    entry.getKey(), group.size(),
                    d.hitRate(), l.hitRate(), h.hitRate(), d.mrr(), l.mrr(), h.mrr()));
        }

        out.append("\n## Question by question\n\n")
                .append("| # | kind | question | dense | lexical | hybrid |\n")
                .append("|---|---|---|---|---|---|\n");
        for (Outcome outcome : outcomes) {
            out.append(String.format("| %s | %s | %s | %s | %s | %s |%n",
                    outcome.testCase().id(), outcome.testCase().kind(),
                    outcome.testCase().question(),
                    position(outcome.denseRank()),
                    position(outcome.lexicalRank()),
                    position(outcome.hybridRank())));
        }
        out.append("\n`#n` is the position of the expected source; `-` means it was not retrieved.\n");
        return out.toString();
    }

    /** One row per question, raw ranks, for a spreadsheet. Rank 0 means "not retrieved". */
    private String buildCsv(List<Outcome> outcomes) {
        StringBuilder out = new StringBuilder();
        out.append(String.join(CSV_SEPARATOR,
                "id", "kind", "question", "expected_source",
                "dense_rank", "lexical_rank", "hybrid_rank",
                "dense_hit", "lexical_hit", "hybrid_hit")).append('\n');
        for (Outcome outcome : outcomes) {
            out.append(String.join(CSV_SEPARATOR,
                    outcome.testCase().id(),
                    outcome.testCase().kind(),
                    escape(outcome.testCase().question()),
                    escape(outcome.testCase().expectedSource()),
                    String.valueOf(outcome.denseRank()),
                    String.valueOf(outcome.lexicalRank()),
                    String.valueOf(outcome.hybridRank()),
                    hit(outcome.denseRank()),
                    hit(outcome.lexicalRank()),
                    hit(outcome.hybridRank()))).append('\n');
        }
        return out.toString();
    }

    /** The separator must never appear inside a field or the columns shift. */
    private static String escape(String field) {
        return field.replace(CSV_SEPARATOR, ",");
    }

    private static String hit(int rank) {
        return (rank > 0 && rank <= TOP_K) ? "1" : "0";
    }

    private static String position(int rank) {
        return rank == 0 ? "-" : "#" + rank;
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
                    String[] fields = line.split(CSV_SEPARATOR, -1);
                    cases.add(new GoldenCase(fields[0].trim(), fields[1].trim(),
                            fields[2].trim(), fields[3].trim()));
                }
                return cases;
            }
        }
    }
}