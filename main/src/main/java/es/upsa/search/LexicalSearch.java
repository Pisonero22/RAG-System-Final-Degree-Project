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
            "cuando", "cuándo", "como", "cómo", "donde", "dónde", "quien", "quién","cuántas",
            "cuantos","cuantas",
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
            "say", "says", "tell", "give", "show", "much", "many", "there", "about", "with", "from", "into",
            "cost","costs","long"


    );
    /**
     * A message only needs rewriting when it cannot stand on its own. Measured: in a
     * 12-question run, 8 rewrites were triggered, none of them was elliptical, and all 8
     * translated the query into the language of the prompt, breaking the lexical branch.
     * Skipping the rewriter for self-contained messages removes the cost AND the risk.
     */


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
     * The lexical branch abstains when the query cannot be a useful literal search.
     *
     * The dangerous case is a SINGLE bare term: "y el 7?" yields the query "7", and a lone
     * digit matches hundreds of prices, identifiers and week numbers across the corpus.
     *
     * Two or more terms already make a conjunctive search meaningful even when none of them is
     * a long word. Measured: the query "SKU 2041" was rejected by the previous rule (it has no
     * word of four letters or more) even though the lexical branch is the ONLY one able to
     * resolve an identifier — for that same query the dense branch ranked the exact row 4th out
     * of 9, inside a score range of 0.018.
     */
    static boolean isWorthSearching(String query) {
        if (query.isBlank()) {
            return false;
        }
        return query.split("\\s+").length >= 2 || CONTENT_WORD.matcher(query).find();
    }
    /**
     * Fragmentos ordenados por relevancia BM25, junto con la query enviada.
     * Solo importa el ORDEN: la fusión posterior usa posiciones, no puntuaciones.
     *
     * Ante cualquier fallo devuelve una lista vacía: igual que la reescritura de
     * query, esta etapa es una mejora y nunca un punto de fallo del sistema.
     */
    public LexicalResult search(String question, int limit) {
        String query = toRediSearchQuery(question);
        if (!isWorthSearching(query)) {
            return LexicalResult.empty(query);
        }
        try {
            QueryArgs args = new QueryArgs().limit(0, limit);
            RETURNED_FIELDS.forEach(args::returnAttribute);

            SearchQueryResponse response = redis.search().ftSearch(index, query, args);

            List<Chunk> chunks = response.documents().stream()
                    .map(LexicalSearch::toChunk)
                    .filter(Objects::nonNull)
                    .toList();
            return new LexicalResult(query, chunks);

        } catch (Exception e) {
            log.warn("Búsqueda léxica fallida ('{}'), se continúa solo con la densa: {}",
                    query, e.toString());
            return LexicalResult.empty(query);
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
    static String toRediSearchQuery(String question) {
        if (question == null) {
            return "";
        }
        return TERM.matcher(question)
                .results()
                .map(MatchResult::group)
                .filter(t -> !STOP_WORDS.contains(t.toLowerCase()))
                .collect(Collectors.joining(" "));
    }


    /** Un documento de RediSearch -> Chunk. Devuelve null si no trae text. */
    private static Chunk toChunk(Document document) {
        String text = property(document, "scalar");
        if (text == null || text.isBlank()) {
            return null;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String field : RETURNED_FIELDS) {
            if (!"scalar".equals(field)) {
                String value = property(document, field);
                if (value != null) {
                    metadata.put(field, value);
                }
            }
        }
        return new Chunk(text, Sources.format(metadata));
    }

    /** Lee una propiedad del documento; null si no viene (p. ej. 'page' en un CSV). */
    private static String property(Document document, String name) {
        Document.Property p = document.property(name);
        return (p == null) ? null : p.asString();
    }
}
