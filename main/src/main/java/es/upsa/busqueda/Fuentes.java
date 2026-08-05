package es.upsa.busqueda;

import java.util.Map;

/**
 * Formatea la procedencia de un fragmento a partir de sus metadatos.
 *
 * Sin estado y sin dependencias, así que no es un bean CDI: es una función.
 * La usan las dos búsquedas, de modo que un PDF se cita igual venga de donde
 * venga, y además queda aislada para poder probarla sin levantar la aplicación.
 *
 * Acepta Map<String,?> porque los metadatos llegan de dos sitios distintos:
 * de langchain4j (Metadata.toMap(), con valores tipados) y de RediSearch
 * (campos devueltos por FT.SEARCH, siempre cadenas).
 */
public final class Fuentes {

    private Fuentes() {}   // clase de utilidad: no se instancia

    public static String formatear(Map<String, ?> metadata) {
        if (metadata.containsKey("file")) {                       // PDF
            Object page = metadata.get("page");
            return cleanName(metadata.get("file"))
                    + (page == null ? "" : " (page " + number(page) + ")");
        }
        if (metadata.containsKey("file_name")) {                  // TXT
            return cleanName(metadata.get("file_name"));
        }

        if (metadata.containsKey("nombre")) {                     // CSV
            Object fila = metadata.get("fila");
            return cleanName(metadata.get("nombre"))
                    + (fila == null ? "" : " (fila " + number(fila) + ")");
        }
        return "fuente desconocida";
    }
    /**
     * Rodea los saltos de línea con espacios.
     *
     * RediSearch no considera el salto de línea un separador de términos: en
     * "utilizar\ntrajes" ambas palabras quedan fundidas en un único término y
     * ninguna de las dos es buscable. Medido en este corpus, eso dejaba fuera del
     * índice léxico en torno al 10% de las palabras (una línea de PDF son ~14
     * palabras, y cada salto inutiliza las dos que tiene a los lados).
     *
     * Los saltos se conservan porque el segmentador recursivo los usa para
     * respetar los límites de párrafo; solo se separan de las palabras vecinas.
     * La búsqueda densa no se ve afectada por este problema ni por su arreglo.
     */
    public static String separarSaltos(String text) {
        return text.replaceAll("[ \\t]*\\r?\\n[ \\t]*", " \n ");
    }
    /** "bd49ab9c-..._Productos.csv" -> "Productos.csv" (prefijo UUID de las subidas). */
    private static String cleanName(Object valor) {
        return String.valueOf(valor).replaceFirst("^[0-9a-fA-F-]{36}_", "");
    }

    /** Los números pueden llegar como Double (16.0) desde langchain4j o como
     *  cadena ("16") desde RediSearch: en ambos casos se muestran sin decimales. */
    private static String number(Object valor) {
        return (valor instanceof Number n) ? String.valueOf(n.longValue()) : String.valueOf(valor);
    }

}
