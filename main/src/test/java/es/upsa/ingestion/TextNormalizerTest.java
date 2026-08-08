package es.upsa.ingestion;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the ingestion clean-up. RediSearch does not treat a line break as a term
 * separator, so "utilizar\ntrajes" is indexed as ONE unsearchable term and both words are lost.
 * Measured on this corpus, that removed roughly 10 % of the words from the lexical index.
 */
public class TextNormalizerTest {

    @Test
    @DisplayName("a Unix line break becomes a separator, so both neighbouring words are indexed")
    void aUnixLineBreakSeparatesTerms() {
        assertEquals("utilizar \n trajes",
                TextNormalizer.normalizeLineBreaks("utilizar\ntrajes"));
    }

    @Test
    @DisplayName("a Windows line break is handled too: the carriage return must not survive "
            + "glued to the previous word")
    void aWindowsLineBreakSeparatesTerms() {
        String normalized = TextNormalizer.normalizeLineBreaks("utilizar\r\ntrajes");

        assertEquals("utilizar \n trajes", normalized);
        assertTrue(normalized.indexOf('\r') < 0, "a carriage return survived the clean-up");
    }

    @Test
    @DisplayName("the spaces and tabs around a break are absorbed, not duplicated")
    void surroundingWhitespaceIsAbsorbed() {
        assertEquals("utilizar \n trajes",
                TextNormalizer.normalizeLineBreaks("utilizar   \n\ttrajes"));
    }

    @Test
    @DisplayName("text without line breaks is left untouched")
    void textWithoutBreaksIsUntouched() {
        assertEquals("una sola linea", TextNormalizer.normalizeLineBreaks("una sola linea"));
    }

    @Test
    @DisplayName("consecutive breaks are each separated: a blank line must not glue paragraphs")
    void consecutiveBreaksAreEachSeparated() {
        assertEquals("fin \n  \n inicio", TextNormalizer.normalizeLineBreaks("fin\n\ninicio"));
    }


}
