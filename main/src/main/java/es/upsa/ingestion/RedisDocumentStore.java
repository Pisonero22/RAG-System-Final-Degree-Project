package es.upsa.ingestion;

import static dev.langchain4j.data.document.splitter.DocumentSplitters.recursive;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import es.upsa.search.Sources;
import es.upsa.qualifier.loaders.CsvDocumentLoader;
import es.upsa.qualifier.loaders.PdfDocumentLoader;
import es.upsa.qualifier.loaders.TxtDocumentLoader;
import io.quarkiverse.langchain4j.redis.RedisEmbeddingStore;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyScanArgs;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
@RedisStorage
public class RedisDocumentStore implements DocumentStore {


    private static final Logger log = LoggerFactory.getLogger(RedisDocumentStore.class);
    private final AtomicBoolean reindexing = new AtomicBoolean(false);


    @Inject
    RedisEmbeddingStore embeddingStore;
    @Inject
    EmbeddingModel embeddingModel;

    @Inject
    TxtDocumentLoader txtDocumentLoader;
    @Inject
    CsvDocumentLoader CSVDocumentLoader;
    @Inject
    PdfDocumentLoader pdfDocumentLoader;


    @ConfigProperty(name = "rag.location.txt")
    Path txtFile;

    @ConfigProperty(name = "rag.location.csv")
    Path csvFiles;

    @ConfigProperty(name = "rag.location.pdf")
    Path pdfFiles;

    @Inject
    RedisDataSource redis;



    private record Corpus(List<Document> rows, List<Document> prose) {
        boolean vacio() { return rows.isEmpty() && prose.isEmpty(); }
        int total()     { return rows.size() + prose.size(); }
    }


    /**
     * Reset = borrar todas las claves de embeddings y reingestar.
     *
     * AQUÍ YA NO SE HACE FT.DROPINDEX, y es importante entender por qué:
     * la extensión de Redis solo crea el índice UNA vez, al arrancar la aplicación
     * (en el constructor de RedisEmbeddingStore). Si tiras el índice en caliente,
     * nada lo recrea, y todas las búsquedas fallan con "No such index" hasta el
     * siguiente reinicio (verificado: es exactamente lo que pasó al usar /reset
     * en mitad de una sesión).
     *
     * Borrar las claves es suficiente para "resetear" los datos: RediSearch
     * des-indexa automáticamente las claves borradas y re-indexa las nuevas.
     *
     * Caso aparte: si cambias de MODELO DE EMBEDDINGS o de DIMENSIÓN, el esquema
     * del índice sí debe cambiar, y eso exige: parar la app -> redis-cli FLUSHALL
     * -> arrancar (se recrea el índice con el esquema nuevo) -> POST /service/reset.
     */
    @Override
    public void rebuildIndex() throws IOException {
        if (!reindexing.compareAndSet(false, true)) {
            throw new IllegalStateException("A reindex is already running");
        }
        try {
            Corpus corpus = loadCorpus();          // 1) cargar y validar
            long t0 = System.nanoTime();

            if (corpus.vacio()) {
                log.warn("Reset CANCELADO: no se ha podido cargar ningún documento. "
                        + "El índice actual se mantiene intacto.");
                return;                              //    mejor el índice viejo que ninguno
            }
            deleteAllEmbedings();
            index(corpus);

            log.info("Reset completado: {} documentos reindexados en {} ms", corpus.total(),(System.nanoTime() - t0) / 1_000_000);
        } finally {
            reindexing.set(false);
        }
    }

    @Override
    public void ingestSingleFile(Path file) throws IOException {
        String nombre = file.getFileName().toString().toLowerCase();
        boolean esCsv = nombre.endsWith(".csv");

        List<Document> docs;
        if (esCsv)                        docs = CSVDocumentLoader.loadFile(file);
        else if (nombre.endsWith(".pdf")) docs = pdfDocumentLoader.loadFile(file);
        else if (nombre.endsWith(".txt")) docs = txtDocumentLoader.loadFile(file);
        else throw new IllegalArgumentException("Extensión no soportada: " + file);

        var builder = ingestor();
        if (!esCsv) {
            builder.documentSplitter(recursive(512, 128));   // CSV: 1 fila = 1 embedding
        }
        builder.build().ingest(docs);

        log.info("Ingesta incremental de '{}': {} documentos", file.getFileName(), docs.size());
    }

    private void deleteAllEmbedings() {
        var keyCommands = redis.key();
        var cursor = keyCommands.scan(new KeyScanArgs().match("embedding:*").count(500));
        while (cursor.hasNext()) {
            var batch = cursor.next();
            if (!batch.isEmpty()) {
                keyCommands.del(batch.toArray(new String[0]));
            }
        }
    }

    private Corpus loadCorpus() throws IOException {
        List<Document> filas = CSVDocumentLoader.load(csvFiles);
        List<Document> prosa = new ArrayList<>();
        prosa.addAll(txtDocumentLoader.load(txtFile));
        prosa.addAll(pdfDocumentLoader.load(pdfFiles));
        return new Corpus(filas, prosa);
    }

    private void index(Corpus corpus) {
        // CSV: cada fila ya es un documento autocontenido -> SIN segmentador
        if (!corpus.rows().isEmpty()) {
            ingestor().build().ingest(corpus.rows());
        }
        // TXT y PDF: prose -> chunks con solape
        if (!corpus.prose().isEmpty()) {
            ingestor().documentSplitter(recursive(512, 128)).build().ingest(corpus.prose());
        }
        log.info("Ingesta: {} rows CSV + {} docs de text/PDF", corpus.rows().size(), corpus.prose().size());
    }
    /**
     * Ingestor común a los tres caminos.
     *
     * El transformador de segmentos aplica la separación de saltos DESPUÉS del
     * segmentador, que es el único punto en el que el text ya no va a cambiar:
     * el segmentador recursivo parte por párrafos y líneas y vuelve a unir los
     * trozos con su propio separador, de modo que cualquier limpieza hecha en el
     * loader queda deshecha para los documentos de prose. Los CSV no lo notaban
     * porque se ingestan sin segmentador, y ese contraste es el que localizó el
     * problema.
     */
    private EmbeddingStoreIngestor.Builder ingestor() {
        return EmbeddingStoreIngestor.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .textSegmentTransformer(s -> TextSegment.from(Sources.normalizeLineBreaks(s.text()), s.metadata()));
    }



}
