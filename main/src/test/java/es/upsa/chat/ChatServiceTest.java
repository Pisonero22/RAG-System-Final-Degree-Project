package es.upsa.chat;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the two pure decisions of the conversational stage. Both were moved out of the
 * prompt and into code because the criterion is exact and does not need a language model to be
 * applied; these tests are what makes that move worth it.
 */
public class ChatServiceTest {


    @ParameterizedTest
    @ValueSource(strings = {
            "SKU-2041",
            "Cuanto cuesta el SKU-2041?",
            "Cuantas unidades hay de sku-2041?",
            "y el PROD_0104?"
    })
    @DisplayName("an identifier is a complete referent: there is nothing for the history to "
            + "resolve, however short the message is")
    void anIdentifierMakesTheMessageSelfContained(String message) {
        // Regression: "SKU-2041" was classified as elliptical, rewritten into a question about
        // its price — an intent imported from the previous turn — and retrieval went from
        // 9D+1L with the correct row first to 1D+0L with the wrong row.
        assertFalse(ChatService.looksElliptical(message));
    }

    @ParameterizedTest
    @ValueSource(strings = {"¿y cuánto cuesta?", "que precio tiene?", "¿y el 7?", "¿y la normal?"})
    @DisplayName("a short message with no subject of its own is elliptical")
    void aShortMessageWithoutASubjectIsElliptical(String message) {
        assertTrue(ChatService.looksElliptical(message));
    }

    @Test
    @DisplayName("a single digit is not an identifier: chapter numbers must keep working")
    void aSingleDigitIsNotAnIdentifier() {
        assertTrue(ChatService.looksElliptical("¿y el capitulo 5?"));
    }

    @Test
    @DisplayName("a date is not an identifier: the pattern demands letters before the digits")
    void aDateIsNotAnIdentifier() {
        assertTrue(ChatService.looksElliptical("¿y el 2026-03-14?"));
    }

    @Test
    @DisplayName("a long self-contained question is not elliptical")
    void aLongSelfContainedQuestionIsNotElliptical() {
        assertFalse(ChatService.looksElliptical(
                "How long can the final disembarkation last in quarantine?"));
    }

    @Test
    @DisplayName("a follow-up opener makes even a long message elliptical")
    void aFollowUpOpenerMakesTheMessageElliptical() {
        assertTrue(ChatService.looksElliptical(
                "y cual es el precio de la version normal de ese producto"));
    }

    // ---------------------------------------------------- differsOnlyInPunctuation

    @Test
    @DisplayName("adding a full stop to Hola shifts its embedding just enough to cross the "
            + "similarity threshold and retrieve five noise chunks: such a rewrite is discarded")
    void aPurelyCosmeticRewriteIsDetected() {
        assertTrue(ChatService.differsOnlyInPunctuation("¿Hola?", "Hola"));
        assertTrue(ChatService.differsOnlyInPunctuation("¿Cuánto cuesta?", "Cuanto cuesta"));
    }

    @Test
    @DisplayName("a rewrite that adds a word is a real rewrite")
    void aRealRewriteIsNotCosmetic() {
        assertFalse(ChatService.differsOnlyInPunctuation(
                "precio de la PlayStation 5", "¿y cuánto cuesta?"));
    }

    @Test
    @DisplayName("the skeleton drops accents, case and every non-alphanumeric character")
    void theSkeletonKeepsOnlyLettersAndDigits() {
        assertTrue(ChatService.differsOnlyInPunctuation("¡ÁRBOL, 12!", "arbol12"));
    }


}
