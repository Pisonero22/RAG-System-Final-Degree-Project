package es.upsa.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RrfFusionTest {


    private RrfFusion fusion;

    @BeforeEach
    void setUp() {
        fusion = new RrfFusion();
        fusion.k = 60;
        fusion.denseWeight = 1.0;
        fusion.lexicalWeight = 1.0;
    }

    private static Chunk chunk(String text) {
        return new Chunk(text, "source of " + text);
    }

    @Test
    @DisplayName("a chunk found by both branches outranks a chunk found by only one, even when "
            + "that one ranked it first")
    void oneLexicalHitPromotesAFragmentTheDenseBranchBuried() {
        // The measured SKU-2041 case: the correct row is 4th for the dense branch and 1st for
        // the lexical branch; the wrong row is 1st for the dense branch and absent from the
        // lexical one.
        List<Chunk> denseChunks = List.of(
                chunk("wrong row"), chunk("filler A"), chunk("filler B"), chunk("correct row"));
        List<Chunk> lexicalChunks = List.of(chunk("correct row"));

        List<RrfFusion.Result> fused = fusion.fuse(denseChunks, lexicalChunks, 3);

        assertEquals("correct row", fused.get(0).chunk().text(),
                "the fusion failed to promote the fragment both branches agree on");
        assertEquals("D+L", fused.get(0).origin());
        assertEquals("D", fused.get(1).origin());

        // 1/(60+3+1) + 1/(60+0+1) = 0.032018 against 1/(60+0+1) = 0.016393
        assertEquals(1.0 / 64 + 1.0 / 61, fused.get(0).score(), 1e-9);
        assertEquals(1.0 / 61, fused.get(1).score(), 1e-9);
    }

    @Test
    @DisplayName("agreement: when both branches rank the same fragment first it scores twice "
            + "the single-branch value")
    void agreementBetweenBothBranchesDoublesTheScore() {
        List<Chunk> both = List.of(chunk("the bulletin"));

        List<RrfFusion.Result> fused = fusion.fuse(both, both, 3);

        assertEquals(1, fused.size(), "the same text must be merged, not duplicated");
        assertEquals("D+L", fused.get(0).origin());
        assertEquals(2.0 / 61, fused.get(0).score(), 1e-9);
    }

    @Test
    @DisplayName("with one branch empty the fusion preserves the order of the other")
    void anEmptyBranchLeavesTheOtherOrderUntouched() {
        List<Chunk> denseChunks = List.of(chunk("first"), chunk("second"), chunk("third"));

        List<RrfFusion.Result> fused = fusion.fuse(denseChunks, List.of(), 3);

        assertEquals(List.of("first", "second", "third"),
                fused.stream().map(r -> r.chunk().text()).toList());
        assertTrue(fused.stream().allMatch(r -> "D".equals(r.origin())));
    }

    @Test
    @DisplayName("the limit is honoured")
    void theResultIsTruncatedToTheRequestedLimit() {
        List<Chunk> denseChunks = List.of(chunk("a"), chunk("b"), chunk("c"), chunk("d"));

        assertEquals(2, fusion.fuse(denseChunks, List.of(), 2).size());
    }

    @Test
    @DisplayName("two empty branches produce no context rather than an error")
    void twoEmptyBranchesProduceNothing() {
        assertTrue(fusion.fuse(List.of(), List.of(), 3).isEmpty());
    }

    @Test
    @DisplayName("a lexical weight below the dense one silences the lexical branch entirely, "
            + "which is why both weights are 1.0")
    void aLowerLexicalWeightSilencesTheLexicalBranch() {
        // Reproduces the measured decision: with k=60 and a lexical weight of 0.7, the BEST
        // lexical result (0.7/61) scores below the TENTH dense one (1.0/70), so no lexical
        // chunk reaches the final list at all. The conversational noise was solved where it
        // belongs — in the query — and not by penalising a whole branch.
        fusion.lexicalWeight = 0.7;

        List<Chunk> tenDenseChunks = List.of(
                chunk("d1"), chunk("d2"), chunk("d3"), chunk("d4"), chunk("d5"),
                chunk("d6"), chunk("d7"), chunk("d8"), chunk("d9"), chunk("d10"));
        List<Chunk> oneLexicalChunk = List.of(chunk("lexical only"));

        List<RrfFusion.Result> fused = fusion.fuse(tenDenseChunks, oneLexicalChunk, 10);

        assertTrue(fused.stream().noneMatch(r -> "lexical only".equals(r.chunk().text())),
                "with a weight of 0.7 a lexical-only chunk should never reach the final list");

        // And with both weights at 1.0 it does reach it, which is the reason for the choice:
        // it ties with the top dense hit (both score 1/61) instead of being pushed out.
        fusion.lexicalWeight = 1.0;
        List<RrfFusion.Result> balanced = fusion.fuse(tenDenseChunks, oneLexicalChunk, 3);

        assertTrue(balanced.stream().anyMatch(r -> "lexical only".equals(r.chunk().text())),
                "with equal weights the top lexical hit must survive into the final list");
        assertEquals(1.0 / 61, balanced.get(0).score(), 1e-9);
    }

}
