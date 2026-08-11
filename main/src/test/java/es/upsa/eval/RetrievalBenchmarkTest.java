package es.upsa.eval;

import es.upsa.search.Chunk;
import es.upsa.search.DenseSearch;
import es.upsa.search.LexicalSearch;
import es.upsa.search.RrfFusion;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
import java.util.*;
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


     /** Semicolon, not comma: questions contain commas and Excel opens it straight away. */
     private static final String GOLDEN_SET = "/eval/golden-set.csv";
     private static final String CSV_SEPARATOR = ";";

    @Inject
    DenseSearch dense;

    @Inject
    LexicalSearch lexical;

    @Inject
    RrfFusion fusion;

    /**
     * Injected, not a constant: the benchmark must measure the SAME number of candidates the
     * application actually uses. A hard-coded 10 that happens to match today would silently stop
     * matching the day rag.retriever.candidates is swept — and the report would still claim it
     * measured the running configuration.
     */
    @ConfigProperty(name = "rag.retriever.candidates")
    int candidates;

    /** One row of the golden set. */
    private record GoldenCase(String id, String question, String expectedSource, String kind) {}

    /** What the three strategies did with one question. Rank is 1-based; 0 means not found. */
    private record Outcome(GoldenCase testCase, int denseRank, int lexicalRank, int hybridRank) {}

    /** hit@k and MRR for one strategy over a set of outcomes. */
    private record Metric(String name, double hitRate, double mrr, int total) {}

    /** Rows whose expected answer is NOT in the corpus. Scored apart: see measureAbstention. */
    private static final String ABSTENTION = "abstention";

    @Test
    void compareTheThreeRetrievalStrategies() throws IOException {
        List<GoldenCase> everything = loadGoldenSet();
        List<GoldenCase> goldenSet = everything.stream()
                .filter(c -> !ABSTENTION.equals(c.kind())).toList();
        List<GoldenCase> abstentions = everything.stream()
                .filter(c -> ABSTENTION.equals(c.kind())).toList();
        assertTrue(goldenSet.size() >= 10, "the golden set is too small to mean anything");

        List<Outcome> outcomes = new ArrayList<>();
        for (GoldenCase testCase : goldenSet) {
            List<Chunk> denseChunks = dense.search(testCase.question());
            List<Chunk> lexicalChunks = lexical.search(testCase.question(), candidates).chunks();
            List<Chunk> fusedChunks = fusion.fuse(denseChunks, lexicalChunks, candidates)
                    .stream()
                    .map(RrfFusion.Result::chunk)
                    .toList();

            outcomes.add(new Outcome(testCase,
                    rankOf(denseChunks, testCase.expectedSource()),
                    rankOf(lexicalChunks, testCase.expectedSource()),
                    rankOf(fusedChunks, testCase.expectedSource())));
        }

        Abstention abstention = measureAbstention(abstentions);

        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        Path markdown = write("retrieval-benchmark.md",
                buildMarkdown(outcomes, abstention, timestamp));
        Path csv = write("retrieval-benchmark.csv", buildCsv(outcomes, abstention));
        System.out.print(buildConsoleSummary(outcomes, abstention, timestamp, markdown, csv));

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
        assertTrue(dense.search("Hola").isEmpty(),
                "the dense threshold has become too permissive");
        assertTrue(lexical.search("Hola", candidates).chunks().isEmpty(),
                "the lexical branch is matching a bare greeting");
    }

    /** How many of the unanswerable questions correctly retrieved nothing. */
    private record Abstention(int total, int correct, List<String> leaks) {
        double rate() { return total == 0 ? 1.0 : (double) correct / total; }
    }

    /**
     * Runs the questions whose answer is NOT in the corpus and counts how often the system
     * correctly retrieves nothing.
     *
     * Deliberately NOT folded into hit@3: a question with no correct source would score as a
     * permanent miss and drag the headline number down for a reason that has nothing to do with
     * retrieval quality. It is the OPPOSITE capability and it deserves its own number — a system
     * that never abstains has perfect recall and is useless, because every answer it gives is
     * built on whatever happened to be closest.
     */
    private Abstention measureAbstention(List<GoldenCase> cases) {
        int correct = 0;
        List<String> leaks = new ArrayList<>();
        for (GoldenCase testCase : cases) {
            List<Chunk> denseChunks = dense.search(testCase.question());
            List<Chunk> lexicalChunks = lexical.search(testCase.question(), candidates).chunks();
            List<RrfFusion.Result> fused = fusion.fuse(denseChunks, lexicalChunks, TOP_K);
            if (fused.isEmpty()) {
                correct++;
            } else {
                leaks.add(String.format("%s  %-38s -> %d chunks, first: %s",
                        testCase.id(), truncate(testCase.question(), 38),
                        fused.size(), fused.get(0).chunk().source()));
            }
        }
        return new Abstention(cases.size(), correct, leaks);
    }

    // ------------------------------------------------------------------ metrics

    /**
     * 1-based position of the expected source in the list, or 0 when it is absent.
     *
     * expectedSource may list several sources separated by '|', and the BEST rank among them
     * counts. This is not a convenience: the corpus contains genuine duplicates — every product
     * in inventario_supermercado_masivo.csv appears twice under different brands, and the two
     * space manuals document the same procedures in Spanish and in English. With a single
     * expected source, retrieving the OTHER equally correct row would be scored as a miss and
     * the benchmark would be punishing the system for being right.
     */
    private static int rankOf(List<Chunk> chunks, String expectedSource) {
        int best = 0;
        for (String expected : expectedSource.split("\\|")) {
            String wanted = expected.trim();
            if (wanted.isEmpty()) {
                continue;
            }
            for (int i = 0; i < chunks.size(); i++) {
                if (chunks.get(i).source().contains(wanted)) {
                    if (best == 0 || i + 1 < best) {
                        best = i + 1;
                    }
                    break;
                }
            }
        }
        return best;
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
    private String buildConsoleSummary(List<Outcome> outcomes, Abstention abstention,
                                       String timestamp, Path markdown, Path csv) {
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

        out.append(String.format("%n  ABSTENTION (questions with no answer in the corpus)%n"));
        out.append("  ").append("-".repeat(70)).append('\n');
        out.append(String.format("  correctly retrieved nothing: %d of %d  (%.2f)%n",
                abstention.correct(), abstention.total(), abstention.rate()));
        for (String leak : abstention.leaks()) {
            out.append("  ").append(leak).append('\n');
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

    /** Locale.ROOT so the decimal separator is always a dot, whatever the machine's locale is. */
    private static String decimals(double value, int places) {
        return String.format(Locale.ROOT, "%." + places + "f", value);
    }

    /**
     * Renders a Markdown table with every column padded to its widest cell. GitHub renders it
     * the same either way, but a padded table is also readable as plain text in an editor.
     */
    private static String markdownTable(List<String> headers, List<List<String>> rows) {
        int columns = headers.size();
        int[] width = new int[columns];
        for (int column = 0; column < columns; column++) {
            width[column] = headers.get(column).length();
        }
        for (List<String> row : rows) {
            for (int column = 0; column < columns; column++) {
                width[column] = Math.max(width[column], row.get(column).length());
            }
        }

        StringBuilder out = new StringBuilder();
        appendRow(out, headers, width);
        List<String> separator = new ArrayList<>();
        for (int column = 0; column < columns; column++) {
            separator.add("-".repeat(width[column]));
        }
        appendRow(out, separator, width);
        for (List<String> row : rows) {
            appendRow(out, row, width);
        }
        return out.toString();
    }

    private static void appendRow(StringBuilder out, List<String> cells, int[] width) {
        out.append('|');
        for (int column = 0; column < cells.size(); column++) {
            String cell = cells.get(column);
            out.append(' ').append(cell)
                    .append(" ".repeat(Math.max(0, width[column] - cell.length())))
                    .append(" |");
        }
        out.append('\n');
    }

    /** Markdown with the three tables, ready to paste into the README. */
    /** The three tables, ready to paste into the README. */
    private String buildMarkdown(List<Outcome> outcomes, Abstention abstention, String timestamp) {
        StringBuilder out = new StringBuilder("# Retrieval benchmark\n\n");
        out.append("Run: ").append(timestamp)
                .append(" | questions: ").append(outcomes.size())
                .append(" | candidates per branch: ").append(candidates)
                .append(" | hit@").append(TOP_K).append("\n\n");

        out.append("## Overall\n\n");
        List<List<String>> overall = new ArrayList<>();
        for (Metric metric : List.of(
                metricOf("dense only", outcomes, Outcome::denseRank),
                metricOf("lexical only", outcomes, Outcome::lexicalRank),
                metricOf("hybrid (RRF)", outcomes, Outcome::hybridRank))) {
            overall.add(List.of(metric.name(),
                    decimals(metric.hitRate(), 2), decimals(metric.mrr(), 3)));
        }
        out.append(markdownTable(List.of("strategy", "hit@" + TOP_K, "MRR"), overall));

        out.append("\n## By question type\n\n");
        List<List<String>> byKind = new ArrayList<>();
        for (Map.Entry<String, List<Outcome>> entry : groupByKind(outcomes).entrySet()) {
            List<Outcome> group = entry.getValue();
            Metric d = metricOf("d", group, Outcome::denseRank);
            Metric l = metricOf("l", group, Outcome::lexicalRank);
            Metric h = metricOf("h", group, Outcome::hybridRank);
            byKind.add(List.of(entry.getKey(), String.valueOf(group.size()),
                    decimals(d.hitRate(), 2), decimals(l.hitRate(), 2), decimals(h.hitRate(), 2),
                    decimals(d.mrr(), 3), decimals(l.mrr(), 3), decimals(h.mrr(), 3)));
        }
        out.append(markdownTable(List.of("kind", "n",
                "dense hit@" + TOP_K, "lexical hit@" + TOP_K, "hybrid hit@" + TOP_K,
                "dense MRR", "lexical MRR", "hybrid MRR"), byKind));

        out.append("\n## Abstention\n\n");
        out.append("Questions whose answer is NOT in the corpus. Retrieving nothing is the ")
                .append("correct behaviour; these never enter hit@").append(TOP_K)
                .append(" or MRR.\n\n");
        out.append(markdownTable(List.of("questions", "correct", "rate"),
                List.of(List.of(String.valueOf(abstention.total()),
                        String.valueOf(abstention.correct()),
                        decimals(abstention.rate(), 2)))));
        if (!abstention.leaks().isEmpty()) {
            out.append("\nRetrieved something when it should not have:\n\n");
            for (String leak : abstention.leaks()) {
                out.append("- `").append(leak).append("`\n");
            }
        }

        out.append("\n## Question by question\n\n");
        List<List<String>> perQuestion = new ArrayList<>();
        for (Outcome outcome : outcomes) {
            perQuestion.add(List.of(
                    outcome.testCase().id(), outcome.testCase().kind(),
                    outcome.testCase().question(),
                    position(outcome.denseRank()),
                    position(outcome.lexicalRank()),
                    position(outcome.hybridRank())));
        }
        out.append(markdownTable(
                List.of("#", "kind", "question", "dense", "lexical", "hybrid"), perQuestion));

        out.append("\n`#n` is the position of the expected source; ")
                .append("`-` means it was not retrieved.\n");
        return out.toString();
    }

    /** One row per question, raw ranks, for a spreadsheet. Rank 0 means "not retrieved". */
    private String buildCsv(List<Outcome> outcomes, Abstention abstention) {
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
        out.append(String.join(CSV_SEPARATOR, "ABSTENTION", "abstention",
                "correctly retrieved nothing",
                abstention.correct() + " of " + abstention.total(),
                "", "", "", "", "", decimals(abstention.rate(), 2))).append('\n');
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