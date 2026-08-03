package es.upsa.busqueda;

import java.util.List;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recuperación de contexto (RAG explícito).
 *
 * Orquesta las dos búsquedas y las fusiona; el contexto resultante viaja en el
 * SYSTEM MESSAGE del asistente y no en la memoria conversacional. Motivo
 * (verificado en quarkus-langchain4j 0.26.2): al enganchar un RetrievalAugmentor
 * al AI Service, el UserMessage que se guarda en la ChatMemory es el YA
 * AUMENTADO, de modo que la ventana se llena de contextos antiguos que compiten
 * entre sí.
 */
@ApplicationScoped
public class RagRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagRetriever.class);

    public static final String SIN_CONTEXTO = "(no se ha recuperado ningún documento relevante)";

    @Inject
    BusquedaDensa densa;
    @Inject
    BusquedaLexica lexica;
    @Inject
    FusionRRF fusionRRF;

    /** Con false, el sistema se comporta exactamente como antes (solo búsqueda
     *  densa). Permite medir el efecto de la hibridación en la memoria del TFG. */
    @ConfigProperty(name = "rag.hybrid.enabled", defaultValue = "true")
    boolean hibridoActivo;

    /** Candidatos que aporta cada búsqueda a la fusión. */
    @ConfigProperty(name = "rag.retriever.candidates", defaultValue = "10")
    int candidatos;

    /** Fragmentos que acaban en el contexto del modelo. */
    @ConfigProperty(name = "rag.retriever.max-results", defaultValue = "3")
    int maxResultados;

    /**
     * Devuelve el contexto formateado (con su procedencia) listo para inyectar
     * en el system message. Una sola entrada de log por pregunta, con todo lo
     * necesario para explicar el resultado: tiempo, modelo de embeddings,
     * candidatos de cada rama, CONSULTA LÉXICA enviada y origen de cada
     * fragmento — D (densa), L (léxica) o D+L (ambas).
     */
    public String buscarContexto(String pregunta) {
        long t0 = System.nanoTime();

        List<Fragmento> densos = densa.buscar(pregunta, candidatos);

        BusquedaLexica.ResultadoLexico lexico = hibridoActivo
                ? lexica.buscar(pregunta, candidatos)
                : BusquedaLexica.ResultadoLexico.vacio("(híbrida desactivada)");
        List<Fragmento> lexicos = lexico.fragmentos();

        List<FusionRRF.Resultado> finales = fusionRRF.fusionar(densos, lexicos, maxResultados);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        String cabecera = String.format("RAG (%d ms | emb='%s' | %dD+%dL | lex=\"%s\") \"%s\"",
                ms, densa.modeloEmbeddings(), densos.size(), lexicos.size(),
                lexico.consulta(), pregunta);

        if (finales.isEmpty()) {
            log.debug("{} -> 0 chunks (sin contexto relevante)", cabecera);
            return SIN_CONTEXTO;
        }

        StringBuilder contexto = new StringBuilder();
        StringBuilder resumen = new StringBuilder(cabecera)
                .append(" -> ").append(finales.size()).append(" chunks:");

        for (FusionRRF.Resultado r : finales) {
            resumen.append(String.format("%n   [%-3s %.4f] %-40s %s",
                    r.origen(), r.puntos(), r.fragmento().fuente(),
                    resumir(r.fragmento().texto())));
            contexto.append("- [").append(r.fragmento().fuente()).append("] ")
                    .append(r.fragmento().texto()).append('\n');
        }
        log.debug("{}", resumen);
        return contexto.toString();
    }

    /** Aplana y trunca para que cada fragmento ocupe UNA línea del log. */
    private static String resumir(String texto) {
        String plano = texto.replaceAll("\\s+", " ").trim();
        return plano.length() <= 100 ? plano : plano.substring(0, 100) + "...";
    }
}