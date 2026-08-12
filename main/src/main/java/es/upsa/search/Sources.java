package es.upsa.search;

import java.util.Map;

/**
 * Formats where a chunk came from, out of its metadata.
 *
 * Stateless and dependency-free, so it is not a CDI bean: it is a function. Both searches use it,
 * which is what makes a PDF get cited the same way whichever branch found it, and it can be
 * tested without starting the application.
 *
 * It takes {@code Map<String,?>} because the metadata arrives from two places: langchain4j
 * (Metadata.toMap(), typed values) and RediSearch (FT.SEARCH fields, always strings).
 */
public final class Sources {

    private Sources() {}

    public static String format(Map<String, ?> metadata) {
        if (metadata.containsKey("file")) {                       // PDF
            Object page = metadata.get("page");
            return cleanName(metadata.get("file"))
                    + (page == null ? "" : " (page " + number(page) + ")");
        }
        if (metadata.containsKey("file_name")) {                  // TXT
            return cleanName(metadata.get("file_name"));
        }

        if (metadata.containsKey("nombre")) {                     // CSV
            Object row = metadata.get("fila");
            return cleanName(metadata.get("nombre"))
                    + (row == null ? "" : " (fila " + number(row) + ")");
        }
        return "unknown source";
    }

    /** "bd49ab9c-..._Productos.csv" -> "Productos.csv": strips the UUID prefix uploads add. */
    private static String cleanName(Object value) {
        return String.valueOf(value).replaceFirst("^[0-9a-fA-F-]{36}_", "");
    }

    /** Numbers arrive as a Double (16.0) from langchain4j or as a String ("16") from RediSearch.
     *  Either way they are shown without decimals. */
    private static String number(Object value) {
        return (value instanceof Number n) ? String.valueOf(n.longValue()) : String.valueOf(value);
    }

}
