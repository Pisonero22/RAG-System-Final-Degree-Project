package es.upsa.store.redis;

import static dev.langchain4j.data.document.splitter.DocumentSplitters.recursive;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import es.upsa.providers.storages.RedisStorage;
import es.upsa.store.StorageProvider;
import es.upsa.store.readerFiles.DocumentFromFileCSV;
import es.upsa.store.readerFiles.DocumentFromFilePDF;
import es.upsa.store.readerFiles.DocumentFromFileTxt;
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

@ApplicationScoped
@RedisStorage
public class IngestionRedisConfiguration implements StorageProvider {


    private static final Logger log = LoggerFactory.getLogger(IngestionRedisConfiguration.class);

    @Inject
    RedisEmbeddingStore embeddingStore;
    @Inject
    EmbeddingModel embeddingModel;

    @Inject
    DocumentFromFileTxt documentFromFileTxt;
    @Inject
    DocumentFromFileCSV documentFromFileCSV;
    @Inject
    DocumentFromFilePDF documentFromFilePDF;


    @ConfigProperty(name = "rag.location.txt")
    Path txtFile;

    @ConfigProperty(name = "rag.location.csv")
    Path csvFiles;

    @ConfigProperty(name = "rag.location.pdf")
    Path pdfFiles;

    @Inject
    RedisDataSource redis;


    private record Corpus(List<Document> filas, List<Document> prosa) {
        boolean vacio() { return filas.isEmpty() && prosa.isEmpty(); }
        int total()     { return filas.size() + prosa.size(); }
    }

    @Override
    public void clearIngestionCache() {
        var keyCommands = redis.key();
        var cursor = keyCommands.scan(new KeyScanArgs().match("embedding:*").count(500));
        while (cursor.hasNext()) {
            var batch = cursor.next();
            if (!batch.isEmpty()) {
                keyCommands.del(batch.toArray(new String[0]));
            }
        }
    }




    @Override
    public void ingest () throws IOException {

        // CSV: cada fila ya es un documento autocontenido -> SIN splitter
        List<Document> csvDocs = documentFromFileCSV.load(csvFiles);
        EmbeddingStoreIngestor.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .build()                                  // sin documentSplitter: 1 fila = 1 embedding
                .ingest(csvDocs);

        // TXT y PDF: prosa -> chunks más grandes con solape
        List<Document> textDocs = new ArrayList<>();
        textDocs.addAll(documentFromFileTxt.load(txtFile));
        textDocs.addAll(documentFromFilePDF.load(pdfFiles));
        EmbeddingStoreIngestor.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .documentSplitter(recursive(512, 128))
                .build()
                .ingest(textDocs);

        log.info("Ingesta: {} filas CSV + {} docs de texto/PDF", csvDocs.size(), textDocs.size());
    }

    private Corpus cargarCorpus() throws IOException {
        List<Document> filas = documentFromFileCSV.load(csvFiles);
        List<Document> prosa = new ArrayList<>();
        prosa.addAll(documentFromFileTxt.load(txtFile));
        prosa.addAll(documentFromFilePDF.load(pdfFiles));
        return new Corpus(filas, prosa);
    }

    private void indexar(Corpus corpus) {
        // CSV: cada fila ya es un documento autocontenido -> SIN splitter
        if (!corpus.filas().isEmpty()) {
            EmbeddingStoreIngestor.builder()
                    .embeddingStore(embeddingStore)
                    .embeddingModel(embeddingModel)
                    .build()
                    .ingest(corpus.filas());
        }
        // TXT y PDF: prosa -> chunks con solape
        if (!corpus.prosa().isEmpty()) {
            EmbeddingStoreIngestor.builder()
                    .embeddingStore(embeddingStore)
                    .embeddingModel(embeddingModel)
                    .documentSplitter(recursive(512, 128))
                    .build()
                    .ingest(corpus.prosa());
        }
        log.info("Ingesta: {} filas CSV + {} docs de texto/PDF", corpus.filas().size(), corpus.prosa().size());
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
     * -> arrancar (se recrea el índice con el esquema nuevo) -> POST /service/ingest.
     */
    @Override
    public void resetEmbeddingStore() throws IOException {
        Corpus corpus = cargarCorpus();          // 1) cargar y validar
        long t0 = System.nanoTime();

        if (corpus.vacio()) {
            log.warn("Reset CANCELADO: no se ha podido cargar ningún documento. "
                    + "El índice actual se mantiene intacto.");
            return;                              //    mejor el índice viejo que ninguno
        }
        clearIngestionCache();
        indexar(corpus);

        log.info("Reset completado: {} documentos reindexados en {} ms", corpus.total(),(System.nanoTime() - t0) / 1_000_000);

    }

    @Override
    public void ingestFile(Path file) throws IOException {
        String nombre = file.getFileName().toString().toLowerCase();
        boolean esCsv = nombre.endsWith(".csv");

        List<Document> docs;
        if (esCsv)                        docs = documentFromFileCSV.loadFile(file);
        else if (nombre.endsWith(".pdf")) docs = documentFromFilePDF.loadFile(file);
        else if (nombre.endsWith(".txt")) docs = documentFromFileTxt.loadFile(file);
        else throw new IllegalArgumentException("Extensión no soportada: " + file);

        // Esto preguntar si no lo podria cambiar a la forma que lo pongo yo o si hay alguna razon para ponerlo asi
        var builder = EmbeddingStoreIngestor.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel);
        if (!esCsv) {
            builder.documentSplitter(recursive(512, 128));   // CSV: 1 fila = 1 embedding
        }
        builder.build().ingest(docs);

        log.info("Ingesta incremental de '{}': {} documentos", file.getFileName(), docs.size());
    }


}
