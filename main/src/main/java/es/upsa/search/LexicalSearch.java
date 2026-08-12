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


/**
 * Literal search: sends the question to RediSearch as a conjunction of terms and returns the
 * chunks containing all of them.
 *
 * It finds by the exact WORD, which is what rescues identifiers, codes and figures — "SKU-2041",
 * "Clase IV" — that the dense branch blurs together. In exchange it is blind to any paraphrase
 * that shares no word with the text, and that is what the dense branch covers.
 */
@ApplicationScoped
public class LexicalSearch {

    private static final Logger log = LoggerFactory.getLogger(LexicalSearch.class);

    /** Letters (accents and ñ included) and digits only: drops in one go every symbol RediSearch
     *  reserves for its query syntax (¿ ? : | - @ " ...). */
    private static final Pattern TERM = Pattern.compile("[\\p{L}\\p{N}]+");

    /** Fields asked of Redis: the text itself, plus everything the citation needs. */
    private static final List<String> RETURNED_FIELDS = List.of("scalar", "file", "page", "nombre", "file_name", "fila");
    /**
     * Spanish function words. RediSearch strips the ENGLISH ones on its own and its default list
     * knows nothing about "del", "los" or "que", so without this set they would be searched like
     * any other term. Why that wrecks the ranking is in toRediSearchQuery.
     */
    private static final Set<String> STOP_WORDS = Set.of(
            // --- Spanish: articles, prepositions and conjunctions
            "de", "del", "la", "el", "los", "las", "lo", "al", "un", "una", "unos", "unas",
            "en", "por", "para", "con", "sin", "sobre", "entre", "desde", "hasta", "según",
            "y", "o", "u", "ni", "pero", "aunque", "porque", "si", "no", "ya", "también",
            // --- Spanish: question words
            "que", "qué", "cual", "cuál", "cuáles", "cuanto", "cuánto", "cuánta", "cuántos",
            "cuando", "cuándo", "como", "cómo", "donde", "dónde", "quien", "quién","cuántas",
            "cuantos","cuantas",
            // --- Spanish: verbs and filler that only ever show up in questions
            "es", "son", "ser", "está", "están", "hay", "tiene", "tienen", "dice", "dicen",
            "dime", "dame", "decir", "saber", "sabes", "hace", "hacen", "puede", "pueden",
            "vale", "valen", "cuesta", "cuestan", "sale", "salen",
            // --- Spanish: other very frequent words
            "me", "te", "se", "le", "les", "nos", "mi", "mis", "tu", "tus", "su", "sus",
            "este", "esta", "esto", "estos", "estas", "ese", "esa", "eso", "muy", "más",
            "menos", "todo", "toda", "todos", "todas", "algo", "nada", "otro", "otra",
            // --- English: question words
            "what", "which", "how", "where", "when", "who", "why",
            // --- English: question verbs and filler that never appear in the data
            "does", "do", "did", "is", "are", "was", "were", "can", "could",
            "say", "says", "tell", "give", "show", "much", "many", "there", "about", "with", "from", "into",
            "cost","costs","long"


    );

    @Inject
    RedisDataSource redis;

    @ConfigProperty(name = "rag.redis.index", defaultValue = "embedding-index")
    String index;
    /**
     * What a lexical search returns: the chunks it found and the QUERY actually sent to
     * RediSearch.
     *
     * The query is part of the result because the caller needs it for the log — it is the only
     * thing that explains why the search found what it found. Recomputing it outside would mean
     * running the sanitiser twice and exposing an internal detail of this class.
     */
    public record LexicalResult(String query, List<Chunk> chunks) {
        static LexicalResult empty(String query) {
            return new LexicalResult(query, List.of());
        }
    }
    /** A word of four letters or more. */
    private static final Pattern CONTENT_WORD = Pattern.compile("\\p{L}{4,}");
    /** A term with digits in it: an identifier, a code, a figure. */
    private static final Pattern HAS_DIGIT = Pattern.compile("\\p{N}");

    /**
     * Conjunctive search with progressive relaxation.
     *
     * AND buys precision, but it is brittle: one term missing from the index kills the whole
     * query. Measured: "¿Cuál es el precio del SKU-2041?" produces "precio SKU 2041" and returns
     * ZERO, because the warehouse is in English and no chunk holds "precio" and "SKU" at the same
     * time — dragging down "SKU" and "2041", which would have found the row on their own.
     *
     * When the full conjunction comes back empty, one term is dropped and it runs again. It only
     * relaxes on EMPTY: if the full AND found something, that result is always the better one and
     * is left alone, so the happy path never pays for an extra query.
     */
    static boolean isWorthSearching(String query) {
        if (query.isBlank()) {
            return false;
        }
        return query.split("\\s+").length >= 2 || CONTENT_WORD.matcher(query).find();
    }

    /**
     * Búsqueda conjuntiva con relajación progresiva.
     *
     * El AND da precisión, pero es frágil: un solo término ausente del índice anula la consulta
     * entera. Medido: "¿Cuál es el precio del SKU-2041?" produce "precio SKU 2041" y devuelve CERO,
     * porque el almacén está en inglés y ningún fragmento contiene "precio" y "SKU" a la vez —
     * arrastrando consigo a "SKU" y "2041", que sí habrían encontrado la fila.
     *
     * Cuando la conjunción completa devuelve cero, se reintenta quitando un término y se repite.
     * Solo se relaja ante el VACÍO: si el AND completo encontró algo, ese resultado es siempre mejor
     * y no se toca, de modo que el camino feliz no paga ni una consulta extra.
     */
    public LexicalResult search(String question, int limit) {
        String query = toRediSearchQuery(question);
        if (!isWorthSearching(query)) {
            return LexicalResult.empty(query);
        }

        List<Chunk> chunks = run(query, limit);
        if (!chunks.isEmpty()) {
            return new LexicalResult(query, chunks);
        }

        // The full conjunction came back empty: only NOW do we relax it.
        List<String> terms = new ArrayList<>(List.of(query.split("\\s+")));
        while (terms.size() > 2) {
            int drop = termToDrop(terms);
            if (drop < 0) {
                return LexicalResult.empty(query);      // an identifier that is not in the corpus
            }
            terms.remove(drop);
            String relaxed = String.join(" ", terms);
            chunks = run(relaxed, limit);
            if (!chunks.isEmpty()) {
                log.debug("Lexical query relaxed: \"{}\" -> \"{}\"", query, relaxed);
                return new LexicalResult(relaxed, chunks);
            }
        }
        // Below two terms we stop: a single word left over from relaxing returns anything at all
        // ("capital Mongolia" would end up searching for "capital").
        return LexicalResult.empty(query);
    }

    /**
     * Index of the term that has to go, or -1 when nothing should be dropped.
     *
     * 1. If a term WITH DIGITS is missing from the index, do not relax: "SKU-9999" is not in the
     *    corpus, so the question has no answer, and dropping it turns it into a different
     *    question. Without this rule, "price SKU 9999" would come back with all 25 warehouse rows.
     * 2. If a term WITHOUT digits is missing ("holds", "sepas", "quedan"), that one is the
     *    culprit: it is a word from the question, not from the corpus, and it is killing the
     *    conjunction without contributing anything.
     * 3. If none is missing, the most frequent one goes, being the least discriminating. That is
     *    the "precio SKU 2041" case: all three exist, but "precio" is in 211 chunks and "2041"
     *    in one.
     */
    private int termToDrop(List<String> terms) {
        int absentWord = -1;
        int mostFrequent = -1;
        long mostFrequentCount = -1;

        for (int i = 0; i < terms.size(); i++) {
            String term = terms.get(i);
            long frequency = documentFrequency(term);
            if (frequency == 0) {
                if (HAS_DIGIT.matcher(term).find()) {
                    return -1;                          // rule 1: do not relax
                }
                if (absentWord < 0) {
                    absentWord = i;                     // rule 2
                }
            } else if (frequency > mostFrequentCount) {
                mostFrequentCount = frequency;          // rule 3
                mostFrequent = i;
            }
        }
        return absentWord >= 0 ? absentWord : mostFrequent;
    }

    /**
     * How many chunks contain the term. LIMIT 0 0 asks for the count only, without fetching a
     * single document. On failure it returns the maximum: a Redis problem must not make the
     * system abstain, so that term is treated as the most frequent one and is the first to fall.
     */
    private long documentFrequency(String term) {
        try {
            return redis.search().ftSearch(index, term, new QueryArgs().limit(0, 0)).count();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    /** The actual search, pulled out so the relaxed query can reuse it. */
    private List<Chunk> run(String query, int limit) {
        try {
            QueryArgs args = new QueryArgs().limit(0, limit);
            RETURNED_FIELDS.forEach(args::returnAttribute);
            return redis.search().ftSearch(index, query, args).documents().stream()
                    .map(LexicalSearch::toChunk)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("Lexical search failed ('{}'), carrying on with the dense branch only: {}", query, e.toString());
            return List.of();
        }
    }
    /**
     * Natural-language question -> RediSearch query.
     *
     * Two decisions worth understanding:
     *
     * 1) Length is NOT filtered. The most discriminating terms of a literal search are often the
     *    shortest ones: "IV", "5", a reference code. Throwing them out for being short throws out
     *    exactly what the lexical branch adds over the semantic one.
     * 2) Spanish function words ARE filtered, because RediSearch only knows the English ones
     *    (see STOP_WORDS).
     *
     * Terms are joined with AND — in RediSearch a space means intersection — so a chunk has to
     * contain ALL of them. OR was tried first and was useless: any frequent word dragged in
     * unrelated chunks ("por qué los manuscritos del mar muerto son importantes" returned a row
     * of refrigerators because it matched "por") and those false positives reached the fusion
     * with a high rank.
     *
     * AND is more brittle — one term missing from the index kills the query — but it is the
     * semantics a literal search calls for: if broad candidates were the goal, the dense branch
     * is already there for that. That brittleness is precisely what makes the stop-word filter
     * indispensable: under AND, demanding "de" or "que" is a free condition that can leave the
     * good result out.
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


    /** A RediSearch document -> Chunk. Returns null when it carries no text. */
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

    /** Reads a property off the document; null when it is not there (e.g. 'page' on a CSV row). */
    private static String property(Document document, String name) {
        Document.Property p = document.property(name);
        return (p == null) ? null : p.asString();
    }
}
