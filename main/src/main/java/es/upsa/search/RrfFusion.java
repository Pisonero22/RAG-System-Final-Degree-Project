package es.upsa.search;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion: combina varias listas ordenadas en una sola.
 * Cada chunk suma peso/(k + puesto) por cada lista en la que aparece.
 *
 * Se usan PUESTOS y no puntuaciones porque las dos escalas son incomparables
 * (coseno entre 0,7 y 0,9 frente a BM25 sin techo) y normalizarlas introduce
 * artefactos: una búsqueda en la que todo puntúa mal se estiraría hasta parecer
 * tan buena como una excelente.
 *
 * Con k=60 (el valor convencional) las diferencias entre puestos consecutivos
 * son pequeñas, de modo que aparecer en AMBAS listas pesa más que ser el
 * primero de una sola: exactamente el comportamiento que se busca, porque un
 * chunk hallado por significado Y por literalidad es casi siempre el bueno.
 *
 * No tiene dependencias externas: es una función pura, y por tanto se puede
 * probar sin levantar Redis ni ningún modelo.
 */

@ApplicationScoped
public class RrfFusion {


    @ConfigProperty(name = "rag.fusion.k", defaultValue = "60")
    int k;

    /** Peso de la lista densa en la suma RRF. Ver el javadoc de pesoLexico: ambos van a 1.0. */
    @ConfigProperty(name = "rag.fusion.dense-weight", defaultValue = "1.0")
    double pesoDenso;
    /**
     * Ambas ramas pesan igual. Se probó un peso léxico menor (0,7), pensando en
     * las coincidencias casuales de las preguntas conversacionales, y resultó
     * anular la rama léxica por completo: con k=60, 0,7/61 es menor que 1,0/70,
     * de modo que el PEOR resultado denso superaba al MEJOR resultado léxico y
     * ningún chunk léxico llegaba nunca a los tres finales.
     *
     * El ruido conversacional se resolvió donde corresponde —en la query, con
     * semántica conjuntiva y filtro de palabras vacías— y no penalizando la rama
     * entera.
     */
    @ConfigProperty(name = "rag.fusion.lexical-weight", defaultValue = "1.0")
    double pesoLexico;

    /** Un chunk fusionado: con su origin ("D", "L" o "D+L") y su puntuación. */
    public record Result(Chunk chunk, String origin, double score) {}

    public List<Result> fuse(List<Chunk> densos, List<Chunk> lexicos, int limite) {
        Map<String, Double> puntos = new LinkedHashMap<>();
        Map<String, Chunk> porTexto = new LinkedHashMap<>();
        Map<String, String> origen = new LinkedHashMap<>();

        accumulate(densos, pesoDenso, "D", puntos, porTexto, origen);
        accumulate(lexicos, pesoLexico, "L", puntos, porTexto, origen);

        return puntos.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limite)
                .map(e -> new Result(porTexto.get(e.getKey()), origen.get(e.getKey()), e.getValue()))
                .toList();
    }

    private void accumulate(List<Chunk> lista, double peso, String etiqueta,
                            Map<String, Double> puntos, Map<String, Chunk> porTexto,
                            Map<String, String> origen) {
        for (int i = 0; i < lista.size(); i++) {
            Chunk f = lista.get(i);
            puntos.merge(f.text(), peso / (k + i + 1), Double::sum);
            porTexto.putIfAbsent(f.text(), f);
            origen.merge(f.text(), etiqueta, (ya, nuevo) -> ya + "+" + nuevo);
        }
    }

}
