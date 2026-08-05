package es.upsa.ingestion;

/**
 * Text clean-up applied during ingestion, right before the segments reach the index.
 * Stateless and dependency-free, so it is not a CDI bean: it is a function.
 */
public class TextNormalizer {

    private TextNormalizer() {}

    /**
     * Rodea los saltos de línea con espacios.
     *
     * RediSearch no considera el salto de línea un separador de términos: en
     * "utilizar\ntrajes" ambas palabras quedan fundidas en un único término y ninguna
     * de las dos es buscable. Medido en este corpus, eso dejaba fuera del índice léxico
     * en torno al 10% de las palabras (una línea de PDF son ~14 palabras, y cada salto
     * inutiliza las dos que tiene a los lados).
     *
     * DEBE aplicarse DESPUÉS de segmentar: el segmentador recursivo vuelve a unir sus
     * trozos con su propio separador y deshace cualquier limpieza hecha en el loader.
     */
    public static String normalizeLineBreaks(String text) {
        return text.replaceAll("[ \\t]*\\r?\\n[ \\t]*", " \n ");
    }

}
