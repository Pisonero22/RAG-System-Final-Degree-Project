package es.upsa.ingestion;

/**
 * Text clean-up applied during ingestion, right before the segments reach the index.
 * Stateless and dependency-free, so it is not a CDI bean: it is a function.
 */
public class TextNormalizer {

    private TextNormalizer() {}

    /**
     * Puts a space on each side of every line break.
     *
     * RediSearch does not treat a line break as a term separator: in "utilizar\ntrajes" both
     * words end up fused into one term and neither of them is searchable. Measured on this
     * corpus, that kept around 10 % of the words out of the lexical index — a PDF line is about
     * 14 words, and every break ruins the two sitting either side of it.
     *
     * Must run AFTER the splitter. The recursive splitter joins its pieces back with its own
     * separator and undoes any clean-up done in the loader.
     */
    public static String normalizeLineBreaks(String text) {
        return text.replaceAll("[ \\t]*\\r?\\n[ \\t]*", " \n ");
    }

}
