package es.upsa.search;

import java.util.Map;

/**
 * Formatea la procedencia de un chunk a partir de sus metadatos.
 *
 * Sin estado y sin dependencias, así que no es un bean CDI: es una función.
 * La usan las dos búsquedas, de modo que un PDF se cita igual venga de donde
 * venga, y además queda aislada para poder probarla sin levantar la aplicación.
 *
 * Acepta Map<String,?> porque los metadatos llegan de dos sitios distintos:
 * de langchain4j (Metadata.toMap(), con valores tipados) y de RediSearch
 * (campos devueltos por FT.SEARCH, siempre cadenas).
 */
public final class Sources {

    private Sources() {}   // clase de utilidad: no se instancia

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

    /** "bd49ab9c-..._Productos.csv" -> "Productos.csv" (prefijo UUID de las subidas). */
    private static String cleanName(Object value) {
        return String.valueOf(value).replaceFirst("^[0-9a-fA-F-]{36}_", "");
    }

    /** Los números pueden llegar como Double (16.0) desde langchain4j o como
     *  cadena ("16") desde RediSearch: en ambos casos se muestran sin decimales. */
    private static String number(Object value) {
        return (value instanceof Number n) ? String.valueOf(n.longValue()) : String.valueOf(value);
    }

}
