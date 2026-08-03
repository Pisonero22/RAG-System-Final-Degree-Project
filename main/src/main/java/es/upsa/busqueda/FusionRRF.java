package es.upsa.busqueda;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion: combina varias listas ordenadas en una sola.
 * Cada fragmento suma peso/(k + puesto) por cada lista en la que aparece.
 *
 * Se usan PUESTOS y no puntuaciones porque las dos escalas son incomparables
 * (coseno entre 0,7 y 0,9 frente a BM25 sin techo) y normalizarlas introduce
 * artefactos: una búsqueda en la que todo puntúa mal se estiraría hasta parecer
 * tan buena como una excelente.
 *
 * Con k=60 (el valor convencional) las diferencias entre puestos consecutivos
 * son pequeñas, de modo que aparecer en AMBAS listas pesa más que ser el
 * primero de una sola: exactamente el comportamiento que se busca, porque un
 * fragmento hallado por significado Y por literalidad es casi siempre el bueno.
 *
 * No tiene dependencias externas: es una función pura, y por tanto se puede
 * probar sin levantar Redis ni ningún modelo.
 */

@ApplicationScoped
public class FusionRRF {


    @ConfigProperty(name = "rag.fusion.k", defaultValue = "60")
    int k;

    /** La lista léxica pondera algo menos: en preguntas conversacionales puede
     *  devolver coincidencias casuales, mientras que la densa acierta al no
     *  devolver nada. */
    @ConfigProperty(name = "rag.fusion.peso-denso", defaultValue = "1.0")
    double pesoDenso;
    @ConfigProperty(name = "rag.fusion.peso-lexico", defaultValue = "0.7")
    double pesoLexico;

    /** Un fragmento fusionado: con su origen ("D", "L" o "D+L") y su puntuación. */
    public record Resultado(Fragmento fragmento, String origen, double puntos) {}

    public List<Resultado> fusionar(List<Fragmento> densos, List<Fragmento> lexicos, int limite) {
        Map<String, Double> puntos = new LinkedHashMap<>();
        Map<String, Fragmento> porTexto = new LinkedHashMap<>();
        Map<String, String> origen = new LinkedHashMap<>();

        acumular(densos, pesoDenso, "D", puntos, porTexto, origen);
        acumular(lexicos, pesoLexico, "L", puntos, porTexto, origen);

        return puntos.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limite)
                .map(e -> new Resultado(porTexto.get(e.getKey()), origen.get(e.getKey()), e.getValue()))
                .toList();
    }

    private void acumular(List<Fragmento> lista, double peso, String etiqueta,
                          Map<String, Double> puntos, Map<String, Fragmento> porTexto,
                          Map<String, String> origen) {
        for (int i = 0; i < lista.size(); i++) {
            Fragmento f = lista.get(i);
            puntos.merge(f.texto(), peso / (k + i + 1), Double::sum);
            porTexto.putIfAbsent(f.texto(), f);
            origen.merge(f.texto(), etiqueta, (ya, nuevo) -> ya + "+" + nuevo);
        }
    }

}
