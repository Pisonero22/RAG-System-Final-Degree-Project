package es.upsa.ingestion;

import static dev.langchain4j.data.document.splitter.DocumentSplitters.recursive;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import es.upsa.ingestion.loaders.CsvDocumentLoader;
import es.upsa.ingestion.loaders.PdfDocumentLoader;
import es.upsa.ingestion.loaders.TxtDocumentLoader;
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
    TxtDocumentLoader txtLoader;
    @Inject
    CsvDocumentLoader csvLoader;
    @Inject
    PdfDocumentLoader pdfLoader;


    @ConfigProperty(name = "rag.location.txt")
    Path txtFile;

    @ConfigProperty(name = "rag.location.csv")
    Path csvFiles;

    @ConfigProperty(name = "rag.location.pdf")
    Path pdfFiles;

    @Inject
    RedisDataSource redis;


    /** The corpus split by how it has to be indexed: CSV rows go in whole, prose gets chunked. */
    private record Corpus(List<Document> rows, List<Document> prose) {
        boolean empty() { return rows.isEmpty() && prose.isEmpty(); }
        int total()     { return rows.size() + prose.size(); }
    }


    /**
     * Reset = delete every embedding key and re-ingest.
     *
     * There is NO FT.DROPINDEX here any more, and it matters why: the Redis extension creates the
     * index ONCE, when the application starts (in the RedisEmbeddingStore constructor). Drop the
     * index while it is running and nothing recreates it — every search fails with "No such
     * index" until the next restart. Verified: that is exactly what happened calling /reset in
     * the middle of a session.
     *
     * Deleting the keys is enough to "reset" the data. RediSearch de-indexes deleted keys and
     * indexes new ones on its own.
     *
     * Different story if you change the EMBEDDING MODEL or the DIMENSION: the index schema has to
     * change with it, and that means stop the app -> redis-cli FLUSHALL -> start again (the index
     * is recreated with the new schema) -> POST /service/admin/reset.
     */
    @Override
    public void rebuildIndex() throws IOException {
        if (!reindexing.compareAndSet(false, true)) {
            throw new IllegalStateException("A reindex is already running");
        }
        try {
            Corpus corpus = loadCorpus();
            long t0 = System.nanoTime();

            if (corpus.empty()) {
                log.warn("Reset CANCELADO: no se ha podido cargar ningún documento. "
                        + "El índice actual se mantiene intacto.");
                return;                              //    an old index beats no index
            }
            deleteAllEmbeddings();
            index(corpus);

            log.info("Reset completado: {} documentos reindexados en {} ms", corpus.total(),(System.nanoTime() - t0) / 1_000_000);
        } finally {
            reindexing.set(false);
        }
    }

    @Override
    public void ingestSingleFile(Path file) throws IOException {
        String fileName = file.getFileName().toString().toLowerCase();
        boolean isCsv = fileName.endsWith(".csv");

        List<Document> documents;
        if (isCsv)                        documents = csvLoader.loadFile(file);
        else if (fileName.endsWith(".pdf")) documents = pdfLoader.loadFile(file);
        else if (fileName.endsWith(".txt")) documents = txtLoader.loadFile(file);
        else throw new IllegalArgumentException("Extensión no soportada: " + file);

        var builder = ingestor();
        if (!isCsv) {
            builder.documentSplitter(recursive(512, 128));   // a CSV row is already one embedding
        }
        builder.build().ingest(documents);

        log.info("Ingesta incremental de '{}': {} documentos", file.getFileName(), documents.size());
    }

    private void deleteAllEmbeddings() {
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
        List<Document> rows = csvLoader.load(csvFiles);
        List<Document> prose = new ArrayList<>();
        prose.addAll(txtLoader.load(txtFile));
        prose.addAll(pdfLoader.load(pdfFiles));
        return new Corpus(rows, prose);
    }

    private void index(Corpus corpus) {
        // A CSV row is a self-contained document already: no splitter.
        if (!corpus.rows().isEmpty()) {
            ingestor().build().ingest(corpus.rows());
        }
        // TXT and PDF: prose gets chunked, with overlap.
        if (!corpus.prose().isEmpty()) {
            ingestor().documentSplitter(recursive(512, 128)).build().ingest(corpus.prose());
        }
        log.info("Ingesta: {} rows CSV + {} docs de text/PDF", corpus.rows().size(), corpus.prose().size());
    }
    /**
     * The ingestor shared by all three paths.
     *
     * The segment transformer applies the line-break fix AFTER the splitter, which is the only
     * point where the text is not going to change again: the recursive splitter breaks on
     * paragraphs and lines and then joins the pieces back with its own separator, so any clean-up
     * done in the loader is undone for prose documents. CSVs never showed the problem because
     * they are ingested without a splitter, and that contrast is what tracked it down.
     */
    private EmbeddingStoreIngestor.Builder ingestor() {
        return EmbeddingStoreIngestor.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .textSegmentTransformer(s -> TextSegment.from(TextNormalizer.normalizeLineBreaks(s.text()), s.metadata()));
    }



}
