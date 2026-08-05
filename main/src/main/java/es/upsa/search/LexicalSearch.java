package es.upsa.search;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.search.Document;
import io.quarkus.redis.datasource.search.QueryArgs;
import io.quarkus.redis.datasource.search.SearchQueryResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class LexicalSearch {

    private static final Logger log = LoggerFactory.getLogger(LexicalSearch.class);

    /** Solo letras (tildes y ñ incluidas) y números: descarta de un golpe todos
     *  los símbolos reservados por la sintaxis de RediSearch (¿ ? : | - @ " ...). */
    private static final Pattern TERM = Pattern.compile("[\\p{L}\\p{N}]+");

    /** Campos que se piden a Redis: el text y todo lo necesario para la cita. */
    private static final List<String> RETURNED_FIELDS = List.of("scalar", "file", "page", "nombre", "file_name", "fila");
    /**
     * Palabras funcionales del español. RediSearch elimina automáticamente las
     * palabras vacías del INGLÉS: su lista por defecto no conoce "del", "los" ni
     * "que", de modo que se buscarían como cualquier otro término y, al unir la
     * query con OR, aparecen en casi todos los chunks e inundan el orden
     * de relevancia (observado: "Por qué los manuscritos del mar muerto son
     * importantes" devolvía una fila de refrigeradores por coincidir en "por").
     */
    private static final Set<String> STOP_WORDS = Set.of(
            // artículos, preposiciones y conjunciones
            "de", "del", "la", "el", "los", "las", "lo", "al", "un", "una", "unos", "unas",
            "en", "por", "para", "con", "sin", "sobre", "entre", "desde", "hasta", "según",
            "y", "o", "u", "ni", "pero", "aunque", "porque", "si", "no", "ya", "también",
            // interrogativos
            "que", "qué", "cual", "cuál", "cuáles", "cuanto", "cuánto", "cuánta", "cuántos",
            "cuando", "cuándo", "como", "cómo", "donde", "dónde", "quien", "quién",
            // verbos y muletillas de pregunta
            "es", "son", "ser", "está", "están", "hay", "tiene", "tienen", "dice", "dicen",
            "dime", "dame", "decir", "saber", "sabes", "hace", "hacen", "puede", "pueden",
            "vale", "valen", "cuesta", "cuestan", "sale", "salen",
            // otros muy frecuentes
            "me", "te", "se", "le", "les", "nos", "mi", "mis", "tu", "tus", "su", "sus",
            "este", "esta", "esto", "estos", "estas", "ese", "esa", "eso", "muy", "más",
            "menos", "todo", "toda", "todos", "todas", "algo", "nada", "otro", "otra",
            // interrogatives
            "what", "which", "how", "where", "when", "who", "why",
            // question verbs and fillers that never appear in the data
            "does", "do", "did", "is", "are", "was", "were", "can", "could",
            "say", "says", "tell", "give", "show", "much", "many", "there", "about", "with", "from", "into"
    );

    @Inject
    RedisDataSource redis;

    @ConfigProperty(name = "rag.redis.index", defaultValue = "embedding-index")
    String index;
    /**
     * Lo que devuelve una búsqueda léxica: los chunks encontrados y la
     * CONSULTA que realmente se envió a RediSearch.
     *
     * La query forma parte del resultado porque quien llama la necesita para
     * el log: es el único dato que explica por qué la búsqueda encontró lo que
     * encontró. La alternativa —recalcularla fuera— obligaría a ejecutar el
     * saneador dos veces y a exponer un detalle interno de esta clase.
     */
    public record LexicalResult(String query, List<Chunk> chunks) {
        static LexicalResult empty(String query) {
            return new LexicalResult(query, List.of());
        }
    }
    /** Una palabra de 4 letras o más. */
    private static final Pattern CONTENT_WORD = Pattern.compile("\\p{L}{4,}");

    /**
     * Sin al menos un término sustantivo, la query no puede ser una búsqueda
     * literal útil. Caso observado: "y el 7?" produce la query "7", y un dígito
     * suelto coincide con cientos de precios, identificadores y números de semana
     * del corpus. En esos casos la rama léxica se abstiene y la cobertura la
     * aporta la búsqueda densa, que sí sabe interpretar la pregunta.
     */
    private static boolean hasContentWord(String consulta) {
        return CONTENT_WORD.matcher(consulta).find();
    }
    /**
     * Fragmentos ordenados por relevancia BM25, junto con la query enviada.
     * Solo importa el ORDEN: la fusión posterior usa posiciones, no puntuaciones.
     *
     * Ante cualquier fallo devuelve una lista vacía: igual que la reescritura de
     * query, esta etapa es una mejora y nunca un punto de fallo del sistema.
     */
    public LexicalResult search(String pregunta, int limite) {
        String consulta = toRediSearchQuery(pregunta);

        if (consulta.isBlank() || !hasContentWord(consulta)) {
            return LexicalResult.empty(consulta);
        }
        try {
            QueryArgs args = new QueryArgs().limit(0, limite);
            RETURNED_FIELDS.forEach(args::returnAttribute);

            SearchQueryResponse respuesta = redis.search().ftSearch(index, consulta, args);

            List<Chunk> chunks = respuesta.documents().stream()
                    .map(LexicalSearch::toChunk)
                    .filter(Objects::nonNull)
                    .toList();
            return new LexicalResult(consulta, chunks);

        } catch (Exception e) {
            log.warn("Búsqueda léxica fallida ('{}'), se continúa solo con la densa: {}",
                    consulta, e.toString());
            return LexicalResult.empty(consulta);
        }
    }

    /**
     * Pregunta en lenguaje natural -> query de RediSearch.
     *
     * Dos decisiones que conviene entender:
     *
     * 1) NO se filtra por longitud. Los términos más discriminantes de una
     *    búsqueda literal son a menudo los más cortos: "IV", "5", un código de
     *    referencia. Descartarlos por cortos elimina justo aquello que aporta la
     *    búsqueda léxica frente a la semántica.
     * 2) SÍ se filtran las palabras funcionales del español, porque RediSearch
     *    solo conoce las inglesas (ver VACIAS).
     *
     * Los términos se unen con AND (en RediSearch el espacio es intersección):
     * el chunk debe contener TODOS los términos. Se probó primero con OR y
     * era inservible: cualquier palabra frecuente arrastraba chunks sin
     * relación —"por qué los manuscritos del mar muerto son importantes" devolvía
     * una fila de refrigeradores por coincidir en "por"— y esos falsos positivos
     * llegaban a la fusión con puesto alto.
     *
     * El AND es más frágil (un término ausente del índice anula la query
     * entera) pero es la semántica que corresponde a una búsqueda literal: si se
     * quisieran candidatos amplios, ya está la rama densa para eso. La fragilidad
     * es justo lo que hace imprescindible el filtro de palabras vacías de abajo:
     * con AND, exigir "de" o "que" en el chunk sería una condición gratuita
     * que puede dejar fuera el resultado bueno.
     */
    static String toRediSearchQuery(String pregunta) {
        if (pregunta == null) {
            return "";
        }
        return TERM.matcher(pregunta)
                .results()
                .map(MatchResult::group)
                .filter(t -> !STOP_WORDS.contains(t.toLowerCase()))
                .collect(Collectors.joining(" "));
    }


    /** Un documento de RediSearch -> Chunk. Devuelve null si no trae text. */
    private static Chunk toChunk(Document doc) {
        String texto = property(doc, "scalar");
        if (texto == null || texto.isBlank()) {
            return null;
        }
        Map<String, String> metadatos = new LinkedHashMap<>();
        for (String campo : RETURNED_FIELDS) {
            if (!"scalar".equals(campo)) {
                String valor = property(doc, campo);
                if (valor != null) {
                    metadatos.put(campo, valor);
                }
            }
        }
        return new Chunk(texto, Sources.format(metadatos));
    }

    /** Lee una propiedad del documento; null si no viene (p. ej. 'page' en un CSV). */
    private static String property(Document doc, String nombre) {
        Document.Property p = doc.property(nombre);
        return (p == null) ? null : p.asString();
    }
}
