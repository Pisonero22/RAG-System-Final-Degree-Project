package es.upsa.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

public class LexicalSearchTest {

    @ParameterizedTest
    @ValueSource(strings = {"7", "6", ""})
    @DisplayName("a single bare term is rejected: a lone digit matches hundreds of prices, "
            + "identifiers and week numbers")
    void aSingleBareTermIsRejected(String query) {
        assertFalse(LexicalSearch.isWorthSearching(query));
    }

    @Test
    @DisplayName("a blank query is rejected")
    void aBlankQueryIsRejected() {
        assertFalse(LexicalSearch.isWorthSearching("   "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SKU 2041", "prod 0104", "capítulo 5", "refrigerated storage"})
    @DisplayName("two or more terms make a conjunctive search meaningful even when none of them "
            + "is a long word")
    void twoOrMoreTermsAreSearched(String query) {
        assertTrue(LexicalSearch.isWorthSearching(query));
    }

    @Test
    @DisplayName("the regression this rule was written for: SKU 2041 has no word of four "
            + "letters and the lexical branch is the only one able to resolve an identifier")
    void anIdentifierAloneIsSearched() {
        // Measured: for this very query the dense branch ranked the exact row 4th out of 9,
        // inside a score range of 0.018.
        assertTrue(LexicalSearch.isWorthSearching("SKU 2041"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"refrigeración", "Hola", "manuscritos"})
    @DisplayName("a single long word is still searched")
    void aSingleLongWordIsSearched(String query) {
        assertTrue(LexicalSearch.isWorthSearching(query));
    }
// ---------------------------------------------------------------- toRediSearchQuery

    @Test
    @DisplayName("null is tolerated: this stage is an improvement, never a point of failure")
    void aNullQuestionYieldsAnEmptyQuery() {
        assertEquals("", LexicalSearch.toRediSearchQuery(null));
    }

    @Test
    @DisplayName("punctuation is dropped so that SKU-2041 becomes two searchable terms")
    void punctuationIsSplitIntoTerms() {
        assertEquals("SKU 2041", LexicalSearch.toRediSearchQuery("SKU-2041"));
    }

    @Test
    @DisplayName("English question words never appear in the data and are removed")
    void englishStopWordsAreRemoved() {
        assertEquals("refrigerated storage",
                LexicalSearch.toRediSearchQuery("How much does refrigerated storage cost?"));
    }

    @Test
    @DisplayName("price is NOT a stop word: it appears in all 25 rows of the English inventory")
    void priceIsKeptBecauseItIsInTheData() {
        assertTrue(LexicalSearch.toRediSearchQuery("What is the price of SKU-2042?")
                .contains("price"));
    }



}
